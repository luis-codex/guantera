import contextlib
import io
import tempfile
import unittest
from pathlib import Path

from analizar_registro import analyze, main

TOOLS = Path(__file__).resolve().parent
FIXTURE = TOOLS / "fixtures" / "registro-emulador-api27.txt"
MONITOR_SOURCE = (
    TOOLS.parent / "domain/src/main/kotlin/app/guantera/sonda/domain/usecase/MonitorSuspension.kt"
)

# SINTÉTICA: el emulador no suspende. Texto idéntico al que emite MonitorSuspension.beat().
SUSPENSION_SINTETICA = "2026-11-09 19:40:50.000 | SUSPENSIÓN detectada de 120 s"
FIX_POSTERIOR_SINTETICO = (
    "2026-11-09 19:41:20.000 | Fix GPS lat=-34.6, lon=-58.38, velocidad=sin velocidad, "
    "precisión=20.0 m, satélites usados=0"
)


# Registros FICTICIOS: no son datos de un vehículo real.
FICTICIO_COMPLETO = """\
2026-11-09 10:00:00.000 | Android: 27 (8.1.0)
2026-11-09 10:00:00.001 | Memoria: MemTotal:        1024000 kB
2026-11-09 10:00:01.000 | service create
2026-11-09 10:00:02.000 | Arranque de app
2026-11-09 10:00:03.000 | Primer fix GPS
2026-11-09 10:00:03.001 | Fix GPS lat=-34.0, lon=-58.0, velocidad=10 m/s, precisión=4 m
2026-11-09 10:02:04.000 | Fix GPS lat=-34.1, lon=-58.1, velocidad=20 m/s, precisión=5 m
2026-11-09 10:02:05.000 | SUSPENSIÓN detectada de 12 s
2026-11-09 10:02:06.000 | Broadcast com.qf.action.ACC_OFF: sin extras
2026-11-09 10:02:07.000 | Broadcast android.intent.action.SCREEN_OFF
2026-11-09 10:02:08.000 | service destroy
"""

FICTICIO_TRUNCADO = """\
2026-11-09 10:00:00.000 | service create
2026-11-09 10:00:01.000 | Fix GPS lat=-34.0, lon=-58.0, velocidad=3 m/s
línea cortada sin separador
2026-11-09 10:00:02.000 | Broadcast com.qf.action.READY_GO_SLEEP_PRE:
"""


class AnalizarRegistroTest(unittest.TestCase):
    def test_extrae_metricas_y_veredictos(self):
        result = analyze(FICTICIO_COMPLETO.splitlines())
        self.assertEqual(len(result["fixes"]), 2)
        self.assertAlmostEqual(result["max_speed"], 72.0)
        self.assertEqual(len(result["gaps"]), 1)
        self.assertEqual(result["suspensions"][0][1], 12)
        self.assertEqual(result["service_starts"], 1)
        self.assertEqual(result["service_deaths"], 1)
        self.assertEqual(result["verdicts"]["gps"], "Sí")
        self.assertEqual(result["verdicts"]["fabricante"], "Sí")

    def test_tolera_registro_truncado_y_desconocido(self):
        result = analyze(FICTICIO_TRUNCADO.splitlines())
        self.assertEqual(len(result["fixes"]), 1)
        self.assertEqual(result["unknown"], 1)
        self.assertEqual(result["verdicts"]["android"], "No se puede saber")

    def test_registro_vacio(self):
        result = analyze([])
        self.assertEqual(result["fixes"], [])
        self.assertIsNone(result["max_speed"])
        self.assertEqual(result["verdicts"]["gps"], "No se puede saber")

    def test_cli_imprime_veredictos(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ficticio.txt"
            path.write_text(FICTICIO_COMPLETO, encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(main([str(path)]), 0)
            self.assertIn("VEREDICTOS", output.getvalue())
            self.assertIn("GPS funciona: Sí", output.getvalue())


class RegistroRealTest(unittest.TestCase):
    """Corre contra un export real del emulador API 27 (no inventado)."""

    @classmethod
    def setUpClass(cls):
        cls.lines = FIXTURE.read_text(encoding="utf-8").splitlines()
        cls.result = analyze(cls.lines)

    def test_android_y_ram_muestran_el_valor(self):
        self.assertEqual(self.result["verdicts"]["android"], "27 (8.1.0)")
        self.assertEqual(self.result["verdicts"]["ram"], "2,046,712 kB")

    def test_no_hay_lineas_desconocidas(self):
        self.assertEqual(self.result["unknown"], 0, self.result["unknown_lines"])

    def test_detecta_avisos_candidatos_por_nombre(self):
        self.assertEqual(
            self.result["candidates"],
            [
                "android.intent.action.ACC_OFF",
                "android.intent.action.ACC_ON",
                "com.qf.action.ACC_OFF",
                "com.qf.action.ACC_ON",
                "com.qf.action.READY_GO_SLEEP_PRE",
            ],
        )
        self.assertEqual(self.result["verdicts"]["fabricante"], "Sí")

    def test_cuenta_eventos_reales(self):
        self.assertEqual(len(self.result["fixes"]), 4)
        self.assertEqual(self.result["service_starts"], 1)
        self.assertEqual(self.result["service_deaths"], 1)
        self.assertEqual(self.result["verdicts"]["gps"], "Sí")

    def test_parada_manual_sin_suspension_es_sin_datos(self):
        contact = self.result["contact"]
        self.assertEqual(contact["veredicto"], "sin datos")
        self.assertTrue(any("Detener solicitado" in item for item in contact["evidencia"]))

    def test_cli_sobre_el_fixture(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(main([str(FIXTURE)]), 0)
        text = output.getvalue()
        self.assertIn("Android real: 27 (8.1.0)", text)
        self.assertIn("RAM real: 2,046,712 kB", text)
        self.assertIn("Líneas desconocidas: 0", text)
        self.assertIn("- com.qf.action.ACC_OFF", text)


class RegistroRealV2Test(unittest.TestCase):
    """Export real posterior a la revisión: incluye el re-registro de GPS al despertar."""

    def test_lee_las_lineas_nuevas_sin_desconocidas(self):
        path = TOOLS / "fixtures" / "registro-emulador-api27-v2.txt"
        lines = path.read_text(encoding="utf-8").splitlines()
        self.assertTrue(any("GPS re-registro solicitado al despertar" in l for l in lines))
        result = analyze(lines)
        self.assertEqual(result["unknown"], 0, result["unknown_lines"])
        self.assertEqual(result["verdicts"]["android"], "27 (8.1.0)")

    def test_error_inesperado_es_linea_conocida(self):
        result = analyze(["2026-11-09 10:00:00.000 | Error inesperado en receptor: java.lang.IllegalStateException: x"])
        self.assertEqual(result["unknown"], 0)


class VeredictoContactoTest(unittest.TestCase):
    def test_la_linea_sintetica_coincide_con_la_app(self):
        if MONITOR_SOURCE.exists():
            self.assertIn("SUSPENSIÓN detectada de $seconds s", MONITOR_SOURCE.read_text(encoding="utf-8"))

    def test_suspension_sintetica_y_retomo(self):
        lines = [l for l in FIXTURE_LINES() if "Detener solicitado" not in l and "service destroy" not in l]
        lines += [SUSPENSION_SINTETICA, FIX_POSTERIOR_SINTETICO]
        result = analyze(lines)
        self.assertEqual(result["suspensions"][0][1], 120)
        self.assertEqual(result["unknown"], 0)
        self.assertEqual(result["contact"]["veredicto"], "se suspendió y retomó")
        self.assertTrue(any("120 s" in item for item in result["contact"]["evidencia"]))
        self.assertTrue(any("fix GPS posterior" in item for item in result["contact"]["evidencia"]))

    def test_suspension_sin_fix_posterior_lo_avisa(self):
        result = analyze(["2026-11-09 10:00:00.000 | service create", SUSPENSION_SINTETICA])
        self.assertEqual(result["contact"]["veredicto"], "se suspendió y retomó")
        self.assertIn("sin fix GPS posterior", result["contact"]["evidencia"][-1])

    def test_destroy_sin_detener_es_muerte(self):
        result = analyze(
            [
                "2026-11-09 10:00:00.000 | service create",
                "2026-11-09 10:05:00.000 | service destroy",
            ]
        )
        self.assertEqual(result["contact"]["veredicto"], "murió el servicio")

    def test_proceso_reiniciado_con_servicio_activo_es_muerte(self):
        result = analyze(
            [
                "2026-11-09 10:00:00.000 | Arranque de proceso",
                "2026-11-09 10:00:01.000 | service create",
                "2026-11-09 10:30:00.000 | Arranque de proceso",
                "2026-11-09 10:30:01.000 | Broadcast android.intent.action.BOOT_COMPLETED",
            ]
        )
        self.assertEqual(result["contact"]["veredicto"], "murió el servicio")
        self.assertIn("Arranque de proceso", result["contact"]["evidencia"][0])

    def test_registro_vacio_es_sin_datos(self):
        self.assertEqual(analyze([])["contact"]["veredicto"], "sin datos")

    def test_linea_desconocida_se_cuenta(self):
        result = analyze(["2026-11-09 10:00:00.000 | algo que la app no emite"])
        self.assertEqual(result["unknown"], 1)


def FIXTURE_LINES():
    return FIXTURE.read_text(encoding="utf-8").splitlines()


if __name__ == "__main__":
    unittest.main()
