package com.restos.printing;

import java.util.ArrayList;
import java.util.List;

/**
 * Texto de un ticket con marcas simples, una por línea:
 * «#BIG» grande y centrado, «#B» negrita, «#C» centrado, «#HR» separador; lo demás es texto normal.
 * El mismo texto sirve para la vista previa en pantalla y para generar los bytes ESC/POS.
 */
final class Ticket {
    private final int columns;
    private final List<String> lines = new ArrayList<>();

    /** paperWidth en mm: 80 → 48 columnas, 58 → 32 columnas. */
    Ticket(int paperWidth) {
        this.columns = paperWidth == 58 ? 32 : 48;
    }

    Ticket big(String text) {
        lines.add("#BIG " + cut(text, columns / 2));
        return this;
    }

    Ticket center(String text) {
        wrap(text, columns, "").forEach(line -> lines.add("#C " + line));
        return this;
    }

    /** Texto en negrita; indent es la sangría de todos sus renglones (por ejemplo, notas bajo el plato). */
    Ticket bold(String text, String indent) {
        wrap(text, columns - indent.length(), "").forEach(line -> lines.add("#B " + indent + line));
        return this;
    }

    /** Primer renglón sin sangría y los siguientes con hanging (nombre del plato que no entra en una línea). */
    Ticket boldHanging(String text, String hanging) {
        wrap(text, columns, hanging).forEach(line -> lines.add("#B " + line));
        return this;
    }

    Ticket text(String text, String indent) {
        wrap(text, columns - indent.length(), "").forEach(line -> lines.add(indent + line));
        return this;
    }

    Ticket rule() {
        lines.add("#HR");
        return this;
    }

    String content() {
        return String.join("\n", lines);
    }

    static int columns(int paperWidth) {
        return paperWidth == 58 ? 32 : 48;
    }

    private static String cut(String text, int width) {
        var clean = text == null ? "" : text.trim();
        return clean.length() <= width ? clean : clean.substring(0, width);
    }

    /** Parte el texto en renglones del ancho del papel; los renglones siguientes llevan la sangría. */
    static List<String> wrap(String text, int width, String indent) {
        var result = new ArrayList<String>();
        var words = (text == null ? "" : text.trim()).split("\\s+");
        var line = new StringBuilder();
        var prefix = "";
        for (var word : words) {
            if (word.isEmpty()) continue;
            var room = width - prefix.length();
            while (word.length() > room) {
                if (!line.isEmpty()) { result.add(prefix + line); line.setLength(0); prefix = indent; room = width - prefix.length(); }
                result.add(prefix + word.substring(0, room));
                word = word.substring(room);
                prefix = indent;
                room = width - prefix.length();
            }
            if (line.length() + (line.isEmpty() ? 0 : 1) + word.length() > room) {
                result.add(prefix + line);
                line.setLength(0);
                prefix = indent;
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty() || result.isEmpty()) result.add(prefix + line);
        return result;
    }
}
