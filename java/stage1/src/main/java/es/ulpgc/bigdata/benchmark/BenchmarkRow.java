package es.ulpgc.bigdata.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Una medida = una fila del CSV común (shared/SPEC.md, sección 9):
 *
 *   language,experiment,structure,dataset_size,repetition,metric,value,unit
 *   java,index_build,monolithic,100,1,elapsed,1234.5,ms
 */
public record BenchmarkRow(String language, String experiment, String structure, int datasetSize,
                           int repetition, String metric, double value, String unit) {

    public static final String HEADER =
            "language,experiment,structure,dataset_size,repetition,metric,value,unit";

    /**
     * Los campos de texto no pueden llevar comas, comillas ni saltos de línea: así el CSV
     * no necesita escapar nada y Python, C y cualquier hoja de cálculo lo leen igual.
     */
    private static final Pattern SAFE_TEXT = Pattern.compile("[A-Za-z0-9_.\\-]+");

    public BenchmarkRow {
        requireSafe("language", language);
        requireSafe("experiment", experiment);
        requireSafe("structure", structure);
        requireSafe("metric", metric);
        requireSafe("unit", unit);
        if (datasetSize < 0) {
            throw new IllegalArgumentException("dataset_size negativo: " + datasetSize);
        }
        if (repetition < 1) {
            throw new IllegalArgumentException("repetition empieza en 1: " + repetition);
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("value no es un número: " + value);
        }
    }

    private static void requireSafe(String field, String text) {
        Objects.requireNonNull(text, field);
        if (!SAFE_TEXT.matcher(text).matches()) {
            throw new IllegalArgumentException(field + " no válido para CSV: \"" + text + "\"");
        }
    }

    /** La fila en formato CSV, sin salto de línea final. */
    public String toCsvLine() {
        return String.join(",", language, experiment, structure,
                Integer.toString(datasetSize), Integer.toString(repetition),
                metric, formatValue(value), unit);
    }

    /**
     * Siempre con punto decimal (nunca "1234,5", que partiría la columna), sin notación
     * científica y con 3 decimales como máximo: 1234.5, 12345, 0.001.
     * 3 decimales de milisegundo = microsegundos, más precisión que el ruido de la medida.
     */
    static String formatValue(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.signum() == 0 ? "0" : rounded.toPlainString();
    }

    /** Lee una línea escrita por toCsvLine (útil para comprobar y analizar resultados). */
    public static BenchmarkRow parse(String line) {
        List<String> f = List.of(line.split(",", -1));
        if (f.size() != 8) {
            throw new IllegalArgumentException("Se esperaban 8 columnas: \"" + line + "\"");
        }
        return new BenchmarkRow(f.get(0), f.get(1), f.get(2), Integer.parseInt(f.get(3)),
                Integer.parseInt(f.get(4)), f.get(5), Double.parseDouble(f.get(6)), f.get(7));
    }
}