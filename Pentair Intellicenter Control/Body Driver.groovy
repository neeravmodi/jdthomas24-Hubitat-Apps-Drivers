metadata {
    definition(
        name: "Pentair IntelliCenter Body",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Pool / Spa controller — pump on/off, temperature, set point and heat source"
    ) {
        attribute "switch",          "string"
        attribute "temperature",     "number"
        attribute "heatingSetpoint", "number"
        attribute "maxSetTemp",      "number"
        attribute "heaterMode",      "string"
        attribute "heatSource",      "string"
        attribute "bodyStatus",      "string"
        attribute "tile",            "string"
        attribute "heatLock",        "string"

        command "refresh"

        // ── Pump On / Off ─────────────────────────────────────────
        // Controls ONLY the pump (body activation).
        // NO effect on heat source or set point.
        // Turning on Spa while Pool is running switches Pool off
        // automatically — the controller handles mutual exclusion.
        command "Turn On"
        command "Turn Off"

        // ── Temperature set point ─────────────────────────────────
        command "Set Temperature", [[name: "degrees*", type: "NUMBER", description: "Set point in °F (40–104)"]]

        // ── Heat source ───────────────────────────────────────────
        command "Set Heat Source", [[name: "source*", type: "ENUM",
            description: "Heat source (locked when heat is disabled)",
            constraints: ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]]]

        // ── Heat lockout ──────────────────────────────────────────
        command "Disable Heat"
        command "Enable Heat"
    }

    preferences {
        input "minSetPoint",  "number", title: "Minimum Set Point (°F)",      defaultValue: 40,  required: true
        input "maxSetPoint",  "number", title: "Maximum Set Point (°F)",       defaultValue: 104, required: true
        input "endpointBase", "text",   title: "App Endpoint Base (auto-set)", required: false
        input "debugMode",    "bool",   title: "Debug Logging",                defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Body installed: ${device.displayName}"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

def updated() {
    log.info "IntelliCenter Body updated: ${device.displayName}"
    debounceTile()
}

// ============================================================
// ===================== PUMP ON / OFF =======================
// ============================================================
def on() {
    if (debugMode) log.debug "${device.displayName}: on() — sending STATUS:ON"
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    parent?.setBodyStatus(device.deviceNetworkId, "ON")
    debounceTile()
}

def off() {
    if (debugMode) log.debug "${device.displayName}: off() — sending STATUS:OFF"
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "bodyStatus", value: "Off")
    parent?.setBodyStatus(device.deviceNetworkId, "OFF")
    debounceTile()
}

// ============================================================
// ===================== TEMPERATURE =========================
// ============================================================
def setHeatingSetpoint(temp) {
    sendEvent(name: "heatingSetpoint", value: temp.toInteger(), unit: "°F")
    debounceTile()
}

def adjustSetPointUp() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    "Set Temperature"(current + 1)
}

def adjustSetPointDown() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    "Set Temperature"(current - 1)
}

// ============================================================
// ===================== HEAT SOURCE =========================
// ============================================================
def setHeatSource(source) {
    sendEvent(name: "heatSource", value: source)
    debounceTile()
}

// ============================================================
// ===================== HEAT LOCKOUT ========================
// ============================================================
def "Disable Heat"() {
    log.info "${device.displayName} — heat disabled"
    sendEvent(name: "heatLock", value: "locked")
    debounceTile()
}

def "Enable Heat"() {
    log.info "${device.displayName} — heat enabled"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

// ============================================================
// ===================== COMMAND WRAPPERS ====================
// ============================================================
def "Turn On"()  { on() }
def "Turn Off"() { off() }

def "Set Temperature"(degrees) {
    def temp = degrees.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()
    if (temp < minT || temp > maxT) {
        log.warn "${device.displayName}: set point ${temp}°F out of range (${minT}–${maxT}°F) — ignoring"
        return
    }
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, temp)
    if (debugMode) log.debug "${device.displayName}: set point ${temp}°F sent to controller"
    debounceTile()
}

def "Set Heat Source"(source) {
    if (device.currentValue("heatLock") == "locked") {
        log.warn "${device.displayName} — heat is disabled. Use Enable Heat first."
        return
    }
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
    if (debugMode) log.debug "${device.displayName}: heat source '${source}' sent to controller"
    debounceTile()
}

// ============================================================
// ===================== REFRESH =============================
// ============================================================
def refresh() {
    parent?.componentRefresh(this)
}

// ============================================================
// ===================== TILE DEBOUNCE =======================
// ============================================================
def debounceTile() {
    unschedule(renderTile)
    runIn(3, renderTile)
}

// ============================================================
// ===================== TILE RENDERER =======================
//
// Pump button behaviour:
//   OFF state → solid green "▶ Turn Pump On" (actionable)
//   ON  state → solid red   "■ Pump Already On — Turn Off?" with
//               a separate small green "● Running" badge
//               This makes it impossible to miss that the pump
//               is already running, and the red colour makes
//               the turn-off action feel intentional.
//
// Heat disabled state:
//   A full-width bold red banner replaces the heat source
//   section header, and all source buttons are greyed/disabled.
// ============================================================
def renderTile() {
    def sw       = device.currentValue("switch")           ?: "off"
    def temp     = (device.currentValue("temperature")     ?: 0).toDouble()
    def setpt    = (device.currentValue("heatingSetpoint") ?: 0).toDouble()
    def maxTemp  = (device.currentValue("maxSetTemp")      ?: 104).toDouble()
    def htmode   = device.currentValue("heaterMode")       ?: "—"
    def htsrc    = device.currentValue("heatSource")       ?: "Off"
    def heatLock = device.currentValue("heatLock")         ?: "unlocked"

    def isOn     = (sw == "on")
    def isLocked = (heatLock == "locked")

    def name = device.displayName
    def dni  = device.deviceNetworkId
    def base = endpointBase ?: ""

    def url    = { String cmd -> "${base}/body/${dni}/${cmd}" }
    def srcUrl = { String src -> "${base}/body/${dni}/heatsource/${src.replaceAll(' ','_').toLowerCase()}" }

    // ── Arc gauge geometry ────────────────────────────────────
    def minT      = (minSetPoint ?: 40).toDouble()
    def maxT      = (maxSetPoint ?: 104).toDouble()
    def arcStart  = 125.0
    def arcEnd    = 415.0
    def arcRange  = arcEnd - arcStart
    def clamp     = { v, lo, hi -> Math.max((double)lo, Math.min((double)hi, (double)v)) }
    def tempFrac  = clamp((temp  - minT) / (maxT - minT), 0.0, 1.0)
    def setptFrac = clamp((setpt - minT) / (maxT - minT), 0.0, 1.0)

    def toRad   = { deg -> deg * Math.PI / 180.0 }
    def arcPath = { double s, double e ->
        double cx = 110, cy = 110, r = 88
        double x1 = cx + r * Math.cos(toRad(s - 90))
        double y1 = cy + r * Math.sin(toRad(s - 90))
        double x2 = cx + r * Math.cos(toRad(e - 90))
        double y2 = cy + r * Math.sin(toRad(e - 90))
        "M ${x1.round(2)} ${y1.round(2)} A ${r} ${r} 0 ${((e-s)>180)?1:0} 1 ${x2.round(2)} ${y2.round(2)}"
    }

    def tempAngle  = arcStart + tempFrac  * arcRange
    def setptAngle = arcStart + setptFrac * arcRange
    def dotX = (110 + 88 * Math.cos(toRad(setptAngle - 90))).round(2)
    def dotY = (110 + 88 * Math.sin(toRad(setptAngle - 90))).round(2)

    def trackPath = arcPath(arcStart, arcEnd)
    def setptPath = arcPath(arcStart, setptAngle)
    def tempPath  = arcPath(arcStart, tempAngle)

    // ── Heat source buttons ───────────────────────────────────
    def sources = ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]
    def srcBtns = sources.collect { lbl ->
        def active    = (htsrc?.equalsIgnoreCase(lbl)) ? "ic-src-active" : ""
        def disClass  = isLocked ? "ic-src-disabled" : ""
        def fetchCall = (!isLocked && base) ? "fetch('${srcUrl(lbl)}');" : ""
        "<button class='ic-src ${active} ${disClass}' onclick=\"${fetchCall}\" ${isLocked ? 'disabled' : ''}>${lbl}</button>"
    }.join("")

    // ── Heat section header — bold red banner when disabled ───
    // Shows a prominent full-width "HEATING DISABLED" bar so
    // there's no ambiguity about why heat buttons are greyed out.
    def heatSectionHtml
    if (isLocked) {
        heatSectionHtml = """
    <div class='ic-heat-disabled-banner'>
      <span class='ic-heat-disabled-icon'>🔥</span>
      HEATING DISABLED
      <div class='ic-heat-disabled-sub'>Use "Enable Heat" command to restore</div>
    </div>
    <div class='ic-srcbtns ic-src-locked'>${srcBtns}</div>"""
    } else {
        heatSectionHtml = """
    <div class='ic-srclbl'>Heat Source</div>
    <div class='ic-srcbtns'>${srcBtns}</div>"""
    }

    def noBase = !base
        ? "<div class='ic-warn'>⚠ Open app and click Done to activate controls</div>"
        : ""

    // ── Pump control buttons ──────────────────────────────────
    // OFF state: single solid green "Turn Pump On" button
    // ON  state: green "● Already Running" badge (non-clickable)
    //            + red "■ Turn Pump Off" button (prominent, intentional)
    // This colour scheme makes state immediately obvious and
    // the turn-off action feel deliberate, not accidental.
    def onFetch  = base ? "fetch('${url('on')}');"  : ""
    def offFetch = base ? "fetch('${url('off')}');" : ""

    def pumpControlHtml
    if (isOn) {
        pumpControlHtml = """
  <div class='ic-pump-running-badge'>● Pump Already On</div>
  <button class='ic-btn-off-active' onclick="${offFetch}">■  Turn Pump Off</button>"""
    } else {
        pumpControlHtml = """
  <button class='ic-btn-on' onclick="${onFetch}">▶  Turn Pump On</button>
  <div class='ic-pump-off-badge'>● Pump is Off</div>"""
    }

    def btnUpFetch = base ? "fetch('${url('setpointup')}');"   : ""
    def btnDnFetch = base ? "fetch('${url('setpointdown')}');" : ""

    def html = """<style>
.ic{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#112240;border-radius:20px;padding:16px 14px 14px;color:#fff;max-width:260px;margin:0 auto;box-sizing:border-box;}
.ic *{box-sizing:border-box;}
.ic-title{font-size:15px;font-weight:700;text-align:center;margin-bottom:10px;color:#e2e8f0;}
.ic-warn{color:#fbbf24;font-size:10px;text-align:center;margin-bottom:6px;}
.ic-gauge{position:relative;width:200px;height:128px;margin:0 auto 6px;}
.ic-gauge svg{width:200px;height:200px;overflow:visible;}
.ic-center{position:absolute;top:18px;left:50%;transform:translateX(-50%);text-align:center;pointer-events:none;white-space:nowrap;}
.ic-mode{font-size:9px;color:#94a3b8;letter-spacing:1px;text-transform:uppercase;}
.ic-temp{font-size:40px;font-weight:800;line-height:1;color:#fff;}
.ic-unit{font-size:11px;color:#94a3b8;}
.ic-setlbl{font-size:10px;color:#38bdf8;margin-top:2px;}
.ic-row{display:flex;gap:7px;margin-bottom:7px;}
.ic-box{flex:1;background:#1e3a5f;border-radius:11px;padding:8px 6px;text-align:center;}
.ic-blbl{font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.5px;margin-bottom:2px;}
.ic-bval{font-size:14px;font-weight:700;color:#e2e8f0;}
.ic-block{background:#1e3a5f;border-radius:11px;padding:10px 11px;margin-bottom:7px;}
.ic-hdr{display:flex;justify-content:space-between;font-size:9px;color:#94a3b8;text-transform:uppercase;letter-spacing:.5px;margin-bottom:7px;}
.ic-adj-row{display:flex;align-items:center;gap:7px;}
.ic-adj{width:36px;height:36px;border-radius:50%;border:none;background:#0f3460;color:#38bdf8;font-size:20px;font-weight:700;cursor:pointer;flex-shrink:0;}
.ic-setval{flex:1;text-align:center;font-size:22px;font-weight:800;color:#38bdf8;}
.ic-srclbl{font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.5px;margin-bottom:6px;}
.ic-srcbtns{display:flex;flex-wrap:wrap;gap:4px;}
.ic-src{padding:4px 8px;border-radius:7px;border:1.5px solid #2d4a6f;background:#0a1628;color:#64748b;font-size:9px;font-weight:600;cursor:pointer;}
.ic-src-active{border-color:#38bdf8;color:#38bdf8;background:#0f3460;}
.ic-src-disabled{opacity:0.35;cursor:not-allowed;}
.ic-src-locked .ic-src{pointer-events:none;}
/* ── Heat disabled banner ── */
.ic-heat-disabled-banner{background:#7f1d1d;border:2px solid #ef4444;border-radius:10px;padding:10px 12px 8px;margin-bottom:8px;text-align:center;font-size:15px;font-weight:900;color:#fca5a5;letter-spacing:1.5px;text-transform:uppercase;}
.ic-heat-disabled-icon{font-size:18px;display:block;margin-bottom:4px;}
.ic-heat-disabled-sub{font-size:9px;font-weight:400;color:#f87171;margin-top:4px;letter-spacing:0;text-transform:none;}
/* ── Pump buttons ── */
.ic-btn-on{width:100%;padding:14px;border-radius:12px;border:none;background:#15803d;color:#fff;font-size:15px;font-weight:800;cursor:pointer;margin-bottom:6px;box-shadow:0 4px 14px rgba(21,128,61,0.5);}
.ic-btn-on:active{background:#166534;}
.ic-pump-running-badge{width:100%;padding:12px;border-radius:12px;border:2px solid #16a34a;background:#052e16;color:#4ade80;font-size:14px;font-weight:800;text-align:center;margin-bottom:6px;letter-spacing:.3px;}
.ic-btn-off-active{width:100%;padding:14px;border-radius:12px;border:none;background:#991b1b;color:#fff;font-size:15px;font-weight:800;cursor:pointer;margin-bottom:6px;box-shadow:0 4px 14px rgba(153,27,27,0.5);}
.ic-btn-off-active:active{background:#7f1d1d;}
.ic-pump-off-badge{width:100%;padding:8px;border-radius:10px;border:1px solid #374151;background:transparent;color:#4b5563;font-size:11px;font-weight:600;text-align:center;margin-bottom:6px;}
.ic-hint{font-size:9px;color:#475569;text-align:center;margin-top:4px;line-height:1.4;}
</style>
<div class='ic'>
  <div class='ic-title'>${name}</div>
  ${noBase}
  <div class='ic-gauge'>
    <svg viewBox='0 0 220 220'>
      <path d='${trackPath}' stroke='#1e3a5f' stroke-width='13' fill='none' stroke-linecap='round'/>
      <path d='${setptPath}' stroke='#1d4080' stroke-width='13' fill='none' stroke-linecap='round'/>
      <path d='${tempPath}'  stroke='#1d6fbf' stroke-width='13' fill='none' stroke-linecap='round'/>
      <circle cx='${dotX}' cy='${dotY}' r='7' fill='#38bdf8' stroke='#112240' stroke-width='3'/>
    </svg>
    <div class='ic-center'>
      <div class='ic-mode'>${htmode}</div>
      <div class='ic-temp'>${Math.round(temp)}</div>
      <div class='ic-unit'>°F current</div>
      <div class='ic-setlbl'>Set ${Math.round(setpt)}°F</div>
    </div>
  </div>
  <div class='ic-row'>
    <div class='ic-box'><div class='ic-blbl'>Set Point</div><div class='ic-bval'>${Math.round(setpt)}°</div></div>
    <div class='ic-box'><div class='ic-blbl'>Max Temp</div><div class='ic-bval'>${Math.round(maxTemp)}°</div></div>
    <div class='ic-box'><div class='ic-blbl'>Pump</div><div class='ic-bval' style='color:${isOn ? "#4ade80" : "#ef4444"};'>${isOn ? "On" : "Off"}</div></div>
  </div>
  <div class='ic-block'>
    <div class='ic-hdr'><span>Set Point</span><span style='color:#38bdf8;'>${Math.round(setpt)}°F</span></div>
    <div class='ic-adj-row'>
      <button class='ic-adj' onclick="${btnDnFetch}">−</button>
      <div class='ic-setval'>${Math.round(setpt)}°</div>
      <button class='ic-adj' onclick="${btnUpFetch}">+</button>
    </div>
  </div>
  <div class='ic-block'>
    ${heatSectionHtml}
  </div>
  ${pumpControlHtml}
  <div class='ic-hint'>On/Off = pump only. Turning on Spa while Pool runs switches Pool off automatically.</div>
</div>"""

    sendEvent(name: "tile", value: html, displayed: false)
}

