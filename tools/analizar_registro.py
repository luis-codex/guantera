#!/usr/bin/env python3
"""Resume un export de Guantera Sonda sin depender de herramientas externas."""

import re
import sys
from datetime import datetime
from pathlib import Path


LINE_RE = re.compile(
    r"^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{3})?)\s*\|\s*(?P<message>.*)$"
)
SPEED_RE = re.compile(r"velocidad\s*=\s*([0-9]+(?:[.,][0-9]+)?)\s*m/s")
SUSPENSION_RE = re.compile(r"SUSPENSIÓN detectada de\s+([0-9]+)\s+s")
FIX_RE = re.compile(r"Fix GPS\b")
HEADER_RE = re.compile(
    r"^(?P<key>DIAGNÓSTICO DEL EQUIPO|Android|Modelo|Fabricante|Hardware|ABI|Memoria|"
    r"Almacenamiento interno total|WebView|Google Play Services|Proveedores de ubicación)"
    r"(?::\s*(?P<value>.*))?$"
)
REPORT_HEADING = "===== REGISTRO ====="
CANDIDATE_ACTIONS = (
    "com.qf.action.ACC_ON",
    "com.qf.action.ACC_OFF",
    "com.qf.action.READY_GO_SLEEP_PRE",
    "android.intent.action.ACC_ON",
    "android.intent.action.ACC_OFF",
)
# Mensajes que emite la app (domain/RecordEvent, domain/TrackLocation, domain/MonitorSuspension).
KNOWN_MESSAGE_RES = tuple(
    re.compile(pattern)
    for pattern in (
        r"^Arranque de proceso$",
        r"^Arranque de app$",
        r"^Activity visible$",
        r"^Iniciar solicitado$",
        r"^Detener solicitado$",
        r"^service create$",
        r"^service destroy$",
        r"^service onTaskRemoved$",
        r"^No se inició servicio tras arranque: ",
        r"^Broadcast \S+(: .*)?$",
        r"^GPS listener iniciado; proveedor activo=(true|false)$",
        r"^GPS sin permiso ACCESS_FINE_LOCATION$",
        r"^Error al iniciar GPS: ",
        r"^GPS re-registro solicitado al despertar$",
        r"^Error inesperado en .+: ",
        r"^Primer fix GPS$",
        r"^Fix GPS lat=",
        r"^Proveedor (habilitado|deshabilitado): \S+$",
        r"^SUSPENSIÓN detectada de \d+ s$",
        r"^Energía: ",  # formato anterior a la refactorización
        HEADER_RE.pattern,  # cabecera con marca de tiempo en exports antiguos
    )
)


def read_lines(path):
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        raise SystemExit(f"No se pudo leer el archivo: {exc}") from exc


def parse_timestamp(value):
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(value, fmt)
        except ValueError:
            pass
    return None


def analyze(lines):
    events = []
    header = {}
    unknown_lines = []
    for line in lines:
        match = LINE_RE.match(line)
        if not match:
            header_match = HEADER_RE.match(line.strip())
            if header_match:
                header[header_match.group("key")] = (header_match.group("value") or "").strip()
            elif line.strip() and line.strip() != REPORT_HEADING:
                unknown_lines.append(line)
            continue
        if not any(known.match(match.group("message")) for known in KNOWN_MESSAGE_RES):
            unknown_lines.append(line)
        events.append(
            {
                "timestamp": parse_timestamp(match.group("timestamp")),
                "message": match.group("message"),
            }
        )

    fixes = [event for event in events if FIX_RE.search(event["message"])]
    speeds = []
    for event in fixes:
        match = SPEED_RE.search(event["message"])
        if match:
            speeds.append(float(match.group(1).replace(",", ".")) * 3.6)

    gaps = []
    for previous, current in zip(fixes, fixes[1:]):
        if previous["timestamp"] and current["timestamp"]:
            seconds = (current["timestamp"] - previous["timestamp"]).total_seconds()
            if seconds > 60:
                gaps.append((previous["timestamp"], current["timestamp"], seconds))

    suspensions = []
    for event in events:
        match = SUSPENSION_RE.search(event["message"])
        if match:
            suspensions.append((event["timestamp"], int(match.group(1))))

    broadcasts = [
        event["message"]
        for event in events
        if event["message"].startswith("Broadcast ")
    ]
    service_starts = sum(event["message"] == "service create" for event in events)
    service_deaths = sum(event["message"] == "service destroy" for event in events)
    app_starts = sum(event["message"] == "Arranque de app" for event in events)
    energy_events = [
        event["message"]
        for event in events
        if event["message"].startswith("Energía:")
    ]
    acc_events = [
        message
        for message in broadcasts
        if any(token in message.upper() for token in ("ACC_", "READY_GO_SLEEP"))
    ]
    for event in events:
        header_match = HEADER_RE.match(event["message"])
        if header_match:
            header.setdefault(header_match.group("key"), (header_match.group("value") or "").strip())
    android = header.get("Android")
    memory = (header.get("Memoria") or "").replace("MemTotal:", "").strip()
    if not memory or memory == "No disponible":
        memory = None
    has_gps = any(
        "Primer fix GPS" in event["message"] or FIX_RE.search(event["message"])
        for event in events
    )
    candidates = sorted(
        {
            action
            for action in CANDIDATE_ACTIONS
            for message in broadcasts
            if message.split(": ", 1)[0] == f"Broadcast {action}"
        }
    )
    manufacturer_notice = bool(candidates or acc_events)
    contact = judge_contact(events)

    return {
        "events": events,
        "unknown": len(unknown_lines),
        "unknown_lines": unknown_lines,
        "header": header,
        "candidates": candidates,
        "contact": contact,
        "fixes": fixes,
        "max_speed": max(speeds) if speeds else None,
        "gaps": gaps,
        "suspensions": suspensions,
        "broadcasts": broadcasts,
        "service_starts": service_starts,
        "service_deaths": service_deaths,
        "app_starts": app_starts,
        "acc_events": acc_events,
        "verdicts": {
            "android": android or "No se puede saber",
            "ram": memory or "No se puede saber",
            "gps": "Sí" if has_gps else "No se puede saber",
            "contacto": contact["veredicto"],
            "fabricante": "Sí" if manufacturer_notice else "No se puede saber",
        },
    }


VERDICT_SUSPENDED = "se suspendió y retomó"
VERDICT_DIED = "murió el servicio"
VERDICT_NO_DATA = "sin datos"


def judge_contact(events):
    """Decide qué pasó con el servicio al cortar contacto, citando las líneas usadas."""
    deaths, suspensions = [], []
    alive = stop_requested = False
    for index, event in enumerate(events):
        message, when = event["message"], format_time(event["timestamp"])
        if message == "service create":
            alive = True
        elif message == "Detener solicitado":
            stop_requested = True
        elif message == "service destroy":
            if not stop_requested:
                deaths.append((index, f"{when}: 'service destroy' sin 'Detener solicitado' previo"))
            alive = stop_requested = False
        elif message == "Arranque de proceso" and alive:
            deaths.append((index, f"{when}: 'Arranque de proceso' con el servicio activo y sin 'service destroy'"))
            alive = False
        elif SUSPENSION_RE.search(message):
            suspensions.append((index, f"{when}: '{message}'"))

    evidence = [text for _, text in deaths]
    if deaths:
        evidence += [text for _, text in suspensions]
        return {"veredicto": VERDICT_DIED, "evidencia": evidence}
    if suspensions:
        last = suspensions[-1][0]
        later_fix = next((e for e in events[last + 1:] if FIX_RE.search(e["message"])), None)
        evidence = [text for _, text in suspensions]
        evidence.append(
            f"{format_time(later_fix['timestamp'])}: primer fix GPS posterior"
            if later_fix
            else "sin fix GPS posterior: el GPS no está confirmado tras la suspensión"
        )
        return {"veredicto": VERDICT_SUSPENDED, "evidencia": evidence}

    seen = [e["message"] for e in events if e["message"].startswith("Broadcast ") and any(
        token in e["message"].upper() for token in ("ACC_", "READY_GO_SLEEP", "SCREEN_OFF", "SHUTDOWN"))]
    evidence = [f"aviso recibido sin suspensión ni muerte posterior: {m}" for m in seen[:3]]
    if any(e["message"] == "Detener solicitado" for e in events):
        evidence.append("parada manual ('Detener solicitado'), no cuenta como muerte")
    return {"veredicto": VERDICT_NO_DATA, "evidencia": evidence or ["ninguna suspensión, muerte ni aviso de contacto en el registro"]}


def format_time(value):
    return value.strftime("%Y-%m-%d %H:%M:%S") if value else "hora desconocida"


def print_report(result):
    print("DIAGNÓSTICO DEL REGISTRO")
    print(f"Primer fix GPS: {'Sí' if result['fixes'] else 'No'}")
    print(f"Fixes GPS registrados: {len(result['fixes'])}")
    if result["max_speed"] is None:
        print("Velocidad máxima: no disponible")
    else:
        print(f"Velocidad máxima: {result['max_speed']:.1f} km/h")

    print("Huecos de GPS:")
    if result["gaps"]:
        for start, end, seconds in result["gaps"]:
            print(
                f"- {format_time(start)} a {format_time(end)} "
                f"({int(seconds)} s)"
            )
    else:
        print("- ninguno mayor a 60 s")

    print("Suspensiones detectadas:")
    if result["suspensions"]:
        for timestamp, seconds in result["suspensions"]:
            print(f"- {format_time(timestamp)}: {seconds} s")
    else:
        print("- ninguna")

    print("Eventos de pantalla:")
    screen_events = [
        event["message"]
        for event in result["events"]
        if event["message"].startswith("Broadcast android.intent.action.SCREEN_")
    ]
    print("\n".join(f"- {event}" for event in screen_events) or "- ninguno")

    print(f"Arranques de app: {result['app_starts']}")
    print(f"Arranques de servicio: {result['service_starts']}")
    print(f"Paradas de servicio: {result['service_deaths']}")
    print("Avisos candidatos recibidos:")
    print("\n".join(f"- {event}" for event in result["acc_events"]) or "- ninguno")
    print("Avisos candidatos detectados por nombre:")
    print("\n".join(f"- {name}" for name in result["candidates"]) or "- ninguno")
    print(f"Líneas desconocidas: {result['unknown']}")
    for line in result["unknown_lines"]:
        print(f"  ? {line}")

    print("VEREDICTOS")
    labels = (
        ("Android real", "android"),
        ("RAM real", "ram"),
        ("GPS funciona", "gps"),
        ("Qué pasó al sacar el contacto", "contacto"),
        ("Aviso propio del fabricante", "fabricante"),
    )
    for label, key in labels:
        value = result["verdicts"][key]
        if key == "fabricante" and result["candidates"]:
            value = f"{value} ({', '.join(result['candidates'])})"
        print(f"- {label}: {value}")
    print("Evidencia del veredicto de contacto:")
    for item in result["contact"]["evidencia"]:
        print(f"  * {item}")


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        print(f"Uso: {Path(sys.argv[0]).name} REGISTRO.txt", file=sys.stderr)
        return 2
    print_report(analyze(read_lines(argv[0])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
