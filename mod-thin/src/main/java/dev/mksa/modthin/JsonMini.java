package dev.mksa.modthin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser JSON minimo. El mod thin recibe respuestas del agente como String
 * (Bridge → LedgerSink → Ledger usa Agent.Json.write), y necesita reconstruir
 * el arbol Map/List/String/Number/Boolean/null para pintar la UI. Sin deps —
 * el mod thin no enlaza Gson ni Jackson para ahorrarse cargar otro classloader
 * de utilidades.
 *
 * Tolerante con tipos numericos: emite Long si es entero, Double si no. La UI
 * pregunta {@link Number#longValue()}/{@code intValue()} indistintamente.
 */
final class JsonMini {

    private final String s;
    private int i;

    private JsonMini(String src) { this.s = src; }

    static Object parse(String text) {
        if (text == null || text.isEmpty()) return null;
        JsonMini j = new JsonMini(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i < j.s.length()) throw new IllegalArgumentException("json sobrante en pos " + j.i);
        return v;
    }

    private Object value() {
        char c = peek();
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == 't') { expect("true"); return Boolean.TRUE; }
        if (c == 'f') { expect("false"); return Boolean.FALSE; }
        if (c == 'n') { expect("null"); return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            String k = string();
            ws(); if (peek() != ':') throw bad(); i++; ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') { i++; ws(); continue; }
            if (c == '}') { i++; return m; }
            throw bad();
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++; ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; ws(); continue; }
            if (c == ']') { i++; return l; }
            throw bad();
        }
    }

    private String string() {
        if (peek() != '"') throw bad();
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw bad();
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw bad();
        if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
            try { return Long.parseLong(n); } catch (NumberFormatException ignored) {}
        }
        return Double.parseDouble(n);
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw bad();
        i += word.length();
    }

    private char peek() {
        if (i >= s.length()) throw bad();
        return s.charAt(i);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException bad() {
        return new IllegalArgumentException("json invalido en pos " + i);
    }
}
