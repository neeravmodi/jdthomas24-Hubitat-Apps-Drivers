metadata {
    definition(
        name: "Pentair IntelliCenter Body",
        namespace: "intellicenter",
        author: "Custom Integration",
        description: "Combined pool/spa controller — on/off, current temp, set point, heater mode and source"
    ) {
        attribute "switch",          "string"
        attribute "temperature",     "number"
        attribute "heatingSetpoint", "number"
        attribute "maxSetTemp",      "number"
        attribute "heaterMode",      "string"
        attribute "heatSource",      "string"
        attribute "bodyStatus",      "string"
        attribute "pendingOn",       "string"
        attribute "tile",            "string"

        command "on"
        command "confirmOn"
        command "off"
        command "setHeatingSetpoint", [[name: "temperature*", type: "NUMBER", description: "New set point (°F)"]]
        command "adjustSetPointUp"
        command "adjustSetPointDown"
        command "setHeatSource", [[name: "source*", type: "ENUM", description: "Heat source",
            constraints: ["OFF", "HEATER", "SOLAR ONLY", "SOLAR PREFERRED", "HEAT PUMP", "HEAT PUMP PREFERRED"]]]
        command "refresh"
    }

    preferences {
        input "minSetPoint",    "number", title: "Minimum Set Point (°F)",       defaultValue: 40,  required: true
        input "maxSetPoint",    "number", title: "Maximum Set Point (°F)",        defaultValue: 104, required: true
        input "confirmTimeout", "number", title: "Confirm On timeout (seconds)",  defaultValue: 10,  required: true
        input "endpointBase",   "text",   title: "App Endpoint Base (auto-set)",  required: false
        input "debugMode",      "bool",   title: "Debug Logging",                 defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Body installed: ${device.displayName}"
    sendEvent(name: "pendingOn", value: "false")
    debounceTile()
}

def updated() {
    log.info "IntelliCenter Body updated: ${device.displayName}"
    debounceTile()
}

// ============================================================
// ===================== ON / OFF WITH CONFIRMATION ==========
// ============================================================
def on() {
    if (device.currentValue("switch") == "on") {
        log.info "${device.displayName} is already on"
        return
    }
    def timeout = (confirmTimeout ?: 10).toInteger()
    if (debugMode) log.debug "on() — pending confirmation (${timeout}s)"
    sendEvent(name: "pendingOn", value: "true")
    runIn(timeout, cancelOn)
    debounceTile()
}

def confirmOn() {
    if (device.currentValue("pendingOn") != "true") {
        log.warn "confirmOn called but no pending on request"
        return
    }
    unschedule(cancelOn)
    sendEvent(name: "pendingOn",  value: "false")
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    if (debugMode) log.debug "confirmOn() — sending ON to controller"
    parent?.setBodyStatus(device.deviceNetworkId, "ON")
    debounceTile()
}

def cancelOn() {
    if (debugMode) log.debug "cancelOn() — confirmation timed out"
    sendEvent(name: "pendingOn", value: "false")
    log.info "${device.displayName} turn-on cancelled (timed out)"
    debounceTile()
}

def off() {
    unschedule(cancelOn)
    sendEvent(name: "pendingOn", value: "false")
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "bodyStatus", value: "Off")
    if (debugMode) log.debug "off() — sending OFF to controller"
    parent?.setBodyStatus(device.deviceNetworkId, "OFF")
    debounceTile()
}

// ============================================================
// ===================== SET POINT ===========================
// ============================================================
def setHeatingSetpoint(temperature) {
    def temp = temperature.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()
    if (temp < minT || temp > maxT) {
        log.warn "Set point ${temp}°F out of range (${minT}–${maxT}°F) — ignoring"
        return
    }
    if (debugMode) log.debug "setHeatingSetpoint: ${temp}°F"
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, temp)
    debounceTile()
}

def adjustSetPointUp() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    setHeatingSetpoint(current + 1)
}

def adjustSetPointDown() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    setHeatingSetpoint(current - 1)
}

// ============================================================
// ===================== HEAT SOURCE =========================
// ============================================================
def setHeatSource(source) {
    if (debugMode) log.debug "setHeatSource: ${source}"
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
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
// Collapses rapid successive updates into a single render
// to avoid excessive CPU load from frequent controller updates.
def debounceTile() {
    unschedule(renderTile)
    runIn(3, renderTile)
}

// ============================================================
// ===================== TILE RENDERER =======================
// ============================================================
def renderTile() {
    def sw      = device.currentValue("switch")          ?: "off"
    def temp    = (device.currentValue("temperature")    ?: 0).toDouble()
    def setpt   = (device.currentValue("heatingSetpoint")?: 0).toDouble()
    def maxTemp = (device.currentValue("maxSetTemp")     ?: 104).toDouble()
    def htmode  = device.currentValue("heaterMode")      ?: "—"
    def htsrc   = device.currentValue("heatSource")      ?: "OFF"
    def pending = device.currentValue("pendingOn")       ?: "false"
    def isOn      = (sw == "on")
    def isPending = (pending == "true")
    def name    = device.displayName
    def dni     = device.deviceNetworkId
    def base    = endpointBase ?: ""

    // URL helpers — spaces in heat source replaced with underscore for URL safety
    def url = { String cmd -> "${base}/body/${dni}/${cmd}" }
    def srcUrl = { String src -> "${base}/body/${dni}/heatsource/${src.replaceAll(' ','_').toLowerCase()}" }

    // Arc geometry — 220° span, SVG 220x220, cx=110 cy=110 r=88
    def minT     = (minSetPoint ?: 40).toDouble()
    def maxT     = (maxSetPoint ?: 104).toDouble()
    def arcStart = 125.0
    def arcEnd   = 415.0
    def arcRange = arcEnd - arcStart
    def clamp    = { v, lo, hi -> Math.max((double)lo, Math.min((double)hi, (double)v)) }
    def tempFrac  = clamp((temp  - minT) / (maxT - minT), 0.0, 1.0)
    def setptFrac = clamp((setpt - minT) / (maxT - minT), 0.0, 1.0)

    def toRad = { deg -> deg * Math.PI / 180.0 }
    def arcPath = { double startDeg, double endDeg ->
        double cx = 110, cy = 110, r = 88
        double x1 = cx + r * Math.cos(toRad(startDeg - 90))
        double y1 = cy + r * Math.sin(toRad(startDeg - 90))
        double x2 = cx + r * Math.cos(toRad(endDeg   - 90))
        double y2 = cy + r * Math.sin(toRad(endDeg   - 90))
        int large = ((endDeg - startDeg) > 180) ? 1 : 0
        "M ${x1.round(2)} ${y1.round(2)} A ${r} ${r} 0 ${large} 1 ${x2.round(2)} ${y2.round(2)}"
    }

    def tempAngle  = arcStart + tempFrac  * arcRange
    def setptAngle = arcStart + setptFrac * arcRange
    def dotX = (110 + 88 * Math.cos(toRad(setptAngle - 90))).round(2)
    def dotY = (110 + 88 * Math.sin(toRad(setptAngle - 90))).round(2)

    def trackPath = arcPath(arcStart, arcEnd)
    def setptPath = arcPath(arcStart, setptAngle)
    def tempPath  = arcPath(arcStart, tempAngle)

    def switchColor = isOn ? "#4ade80" : "#ef4444"
    def switchLabel = isOn ? "● On"    : "● Off"

    // Heat source buttons
    def sources = ["OFF", "HEATER", "SOLAR ONLY", "SOLAR PREFERRED", "HEAT PUMP", "HEAT PUMP PREFERRED"]
    def sourceLabels = ["Off", "Heater", "Solar Only", "Solar Pref.", "Heat Pump", "HP Pref."]
    def srcBtns = [sources, sourceLabels].transpose().collect { src, lbl ->
        def active  = (htsrc == src) ? "ic-src-active" : ""
        def fetchUrl = base ? "fetch('${srcUrl(src)}');" : ""
        "<button class='ic-src ${active}' onclick=\"${fetchUrl}\">${lbl}</button>"
    }.join("")

    // On/off button states
    def btnOnStyle  = isPending ? "background:#fbbf24;color:#0a1628;" : "background:#0f3460;color:#38bdf8;border:1.5px solid #38bdf8;"
    def btnOnLabel  = isPending ? "Cancel"  : "Turn On"
    def btnOnFetch  = base ? (isPending ? "fetch('${url('on')}');" : "fetch('${url('on')}');") : ""
    def confirmDisp = isPending ? "flex" : "none"
    def btnConfFetch = base ? "fetch('${url('confirmOn')}');" : ""
    def btnOffFetch  = base ? "fetch('${url('off')}');"       : ""
    def btnUpFetch   = base ? "fetch('${url('setpointup')}');"   : ""
    def btnDnFetch   = base ? "fetch('${url('setpointdown')}');" : ""

    def noBase = !base ? "<div style='color:#fbbf24;font-size:10px;text-align:center;margin-bottom:6px;'>⚠ Re-open app to enable controls</div>" : ""

    def html = """<style>
.ic{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#112240;border-radius:20px;padding:16px 14px 14px;color:#fff;max-width:260px;margin:0 auto;box-sizing:border-box;}
.ic *{box-sizing:border-box;}
.ic-title{font-size:15px;font-weight:700;text-align:center;margin-bottom:10px;color:#e2e8f0;}
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
.ic-adj{width:34px;height:34px;border-radius:50%;border:none;background:#0f3460;color:#38bdf8;font-size:18px;font-weight:700;cursor:pointer;flex-shrink:0;line-height:1;display:flex;align-items:center;justify-content:center;}
.ic-setval{flex:1;text-align:center;font-size:20px;font-weight:800;color:#38bdf8;}
.ic-srclbl{font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.5px;margin-bottom:6px;}
.ic-srcbtns{display:flex;flex-wrap:wrap;gap:4px;}
.ic-src{padding:4px 8px;border-radius:7px;border:1.5px solid #2d4a6f;background:#0a1628;color:#64748b;font-size:9px;font-weight:600;cursor:pointer;}
.ic-src-active{border-color:#38bdf8;color:#38bdf8;background:#0f3460;}
.ic-pwrow{display:flex;gap:7px;margin-bottom:6px;}
.ic-btn{flex:1;padding:9px 4px;border-radius:11px;border:none;font-size:12px;font-weight:700;cursor:pointer;}
.ic-status{text-align:center;font-size:11px;font-weight:600;}
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
    <div class='ic-srclbl'>Heat Source</div>
    <div class='ic-srcbtns'>${srcBtns}</div>
  </div>
  <div class='ic-pwrow'>
    <button class='ic-btn' style='${btnOnStyle}' onclick="${btnOnFetch}">${btnOnLabel}</button>
    <button class='ic-btn' style='display:${confirmDisp};background:#fbbf24;color:#0a1628;' onclick="${btnConfFetch}">Confirm On</button>
    <button class='ic-btn' style='background:#1e3a5f;color:#94a3b8;' onclick="${btnOffFetch}">Turn Off</button>
  </div>
  <div class='ic-status' style='color:${switchColor};'>${switchLabel}</div>
</div>"""

    sendEvent(name: "tile", value: html, displayed: false)
}

