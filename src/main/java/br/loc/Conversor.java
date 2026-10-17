package br.loc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converte o PDF do Livro de Oração Comum em arquivos JSON (um por seção), que o site
 * em /docs lê e permite editar. Uso:
 *
 *   java -jar loc-conversor.jar  livro.pdf  docs/data  [secoes.txt]
 *
 * Nada do conteúdo do livro fica dentro deste código: o texto sai do PDF que VOCÊ fornece.
 */
public class Conversor {

    // ------------------------------------------------------------------ modelo

    /** Uma linha visual do PDF, já dividida em células quando há colunas. */
    static final class Linha {
        String texto;
        final List<String> celulas = new ArrayList<>();
        final List<Float> inicios = new ArrayList<>();
        boolean negrito, italico;
        float y, tamanho;
    }

    /** Um bloco do site: título, subtítulo, rubrica, texto, verso ou tabela. */
    static final class Bloco {
        String tipo;
        String texto;
        List<List<String>> linhas;   // só para tabela
        boolean cabecalho;           // só para tabela
        int pagina;
    }

    record Secao(String id, String titulo, String modo, int inicio, int fim) {}

    // --------------------------------------------------- extração de linhas (PDFBox)

    /**
     * Junta as peças de texto que o PDFBox entrega em linhas visuais. Usa a posição vertical
     * (Y) para detectar quebra de linha, o que independe de como a versão do PDFBox agrupa
     * as palavras.
     */
    static final class Extrator extends PDFTextStripper {
        final List<Linha> linhas = new ArrayList<>();
        private final List<TextPosition> atual = new ArrayList<>();

        Extrator() throws IOException {
            setSortByPosition(true);
        }

        List<Linha> extrair(PDDocument doc, int pagina) throws IOException {
            linhas.clear();
            atual.clear();
            setStartPage(pagina);
            setEndPage(pagina);
            getText(doc); // o texto devolvido é ignorado; usamos os callbacks
            fecharLinha();
            return new ArrayList<>(linhas);
        }

        @Override
        protected void writeString(String texto, List<TextPosition> posicoes) throws IOException {
            for (TextPosition p : posicoes) {
                if (!atual.isEmpty() && Math.abs(p.getYDirAdj() - atual.get(0).getYDirAdj()) > 2.0f) {
                    fecharLinha();
                }
                atual.add(p);
            }
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            fecharLinha();
            super.endPage(page);
        }

        private void fecharLinha() {
            if (atual.isEmpty()) return;
            List<TextPosition> ps = new ArrayList<>(atual);
            atual.clear();
            ps.sort((a, b) -> Float.compare(a.getXDirAdj(), b.getXDirAdj()));

            float somaLargura = 0;
            int n = 0;
            for (TextPosition p : ps) {
                if (!unicode(p).isBlank()) { somaLargura += p.getWidthDirAdj(); n++; }
            }
            if (n == 0) return;
            float larguraMedia = somaLargura / n;

            Linha l = new Linha();
            l.y = ps.get(0).getYDirAdj();
            l.tamanho = ps.get(0).getFontSizeInPt();
            StringBuilder cel = new StringBuilder();
            float fimAnterior = Float.NaN;
            float inicioCelula = Float.NaN;
            boolean anteriorEspaco = true;
            int negritos = 0, italicos = 0;

            for (TextPosition p : ps) {
                String u = unicode(p);
                boolean espaco = u.isBlank();
                if (!espaco) {
                    String f = nomeFonte(p).toLowerCase(Locale.ROOT);
                    if (f.contains("bold")) negritos++;
                    if (f.contains("italic") || f.contains("oblique")) italicos++;
                }
                float inicio = p.getXDirAdj();
                if (!espaco && !Float.isNaN(fimAnterior)) {
                    float vao = inicio - fimAnterior;
                    if (vao > larguraMedia * 3.0f) {                  // vão grande = nova coluna
                        fecharCelula(l, cel, inicioCelula);
                        inicioCelula = Float.NaN;
                        anteriorEspaco = true;
                    } else if (vao > larguraMedia * 0.25f && !anteriorEspaco) {
                        cel.append(' ');
                    }
                }
                if (!espaco && Float.isNaN(inicioCelula)) inicioCelula = inicio;
                cel.append(espaco ? " " : u);
                anteriorEspaco = espaco;
                fimAnterior = p.getXDirAdj() + p.getWidthDirAdj();
            }
            fecharCelula(l, cel, inicioCelula);

            if (l.celulas.isEmpty()) return;
            l.texto = String.join(" ", l.celulas);
            l.negrito = negritos * 2 > n;
            l.italico = italicos * 2 > n;
            linhas.add(l);
        }

        /** Fecha a célula atual; celulas e inicios sempre andam em pares. */
        private static void fecharCelula(Linha l, StringBuilder cel, float inicio) {
            String t = cel.toString().replaceAll("\\s+", " ").strip();
            cel.setLength(0);
            if (!t.isEmpty() && !Float.isNaN(inicio)) {
                l.celulas.add(t);
                l.inicios.add(inicio);
            }
        }
    }

    // As duas chamadas abaixo mudam entre versões do PDFBox (2.x usa getUnicode/getName;
    // 1.8 usava getCharacter/getBaseFont). Se trocar a versão, ajuste só aqui.
    static String unicode(TextPosition p) { return p.getUnicode(); }
    static String nomeFonte(TextPosition p) { return String.valueOf(p.getFont().getName()); }

    // ------------------------------------------------------------ montagem dos blocos

    static final Pattern ROTULO = Pattern.compile("^\\p{Lu}[\\p{L} .]{0,25}\\s[-–]\\s.*");
    static final Pattern FIM_FRASE = Pattern.compile(".*[.;:!?”\"»)]$");
    static final Pattern CABECALHO_VERSO = Pattern.compile("^\\d+\\s+\\p{L}[\\p{L} ,.'?-]{0,50}$");

    static boolean maiusculas(String t) {
        return t.chars().anyMatch(Character::isLetter) && t.equals(t.toUpperCase(Locale.ROOT));
    }

    /** Remove o rodapé: última linha curta que contém o número da página. */
    static void removerRodape(List<Linha> ls, int pagina) {
        if (ls.isEmpty()) return;
        Linha ultima = ls.get(ls.size() - 1);
        boolean tem = Pattern.compile("(^|\\s)" + pagina + "(\\s|$)").matcher(ultima.texto).find();
        if (tem && ultima.texto.length() <= 70) ls.remove(ls.size() - 1);
    }

    /** Separa em grupos por espaços verticais maiores que a distância normal entre linhas. */
    static List<List<Linha>> agrupar(List<Linha> ls, float fator) {
        List<List<Linha>> grupos = new ArrayList<>();
        if (ls.isEmpty()) return grupos;
        List<Float> difs = new ArrayList<>();
        for (int i = 1; i < ls.size(); i++) {
            float d = ls.get(i).y - ls.get(i - 1).y;
            if (d > 0.9f * ls.get(i).tamanho) difs.add(d);
        }
        float passo = difs.isEmpty() ? ls.get(0).tamanho * 1.2f : Collections.min(difs);
        List<Linha> g = new ArrayList<>();
        g.add(ls.get(0));
        for (int i = 1; i < ls.size(); i++) {
            if (ls.get(i).y - ls.get(i - 1).y > passo * fator) {
                grupos.add(g);
                g = new ArrayList<>();
            }
            g.add(ls.get(i));
        }
        grupos.add(g);
        return grupos;
    }

    static boolean todas(List<Linha> g, boolean negrito) {
        return g.stream().allMatch(l -> negrito ? l.negrito : l.italico);
    }

    static Bloco bloco(String tipo, String texto, int pagina) {
        Bloco b = new Bloco();
        b.tipo = tipo;
        b.texto = texto;
        b.pagina = pagina;
        return b;
    }

    static Bloco classificarProsa(List<Linha> g, int pagina) {
        StringBuilder sb = new StringBuilder();
        for (Linha l : g) {
            if (sb.length() > 0) sb.append(ROTULO.matcher(l.texto).matches() ? '\n' : ' ');
            sb.append(l.texto);
        }
        String t = sb.toString().replaceAll("[ \\t]+", " ").replaceAll(" ?\n ?", "\n").strip();
        boolean curta = g.size() == 1 && t.length() <= 70 && !FIM_FRASE.matcher(t).matches() && !ROTULO.matcher(t).matches();
        if (todas(g, true)) return bloco(maiusculas(t) ? "titulo" : "subtitulo", t, pagina);
        if (t.startsWith("* ") || todas(g, false)) return bloco("rubrica", t, pagina);
        if (curta) return bloco(maiusculas(t) ? "titulo" : "subtitulo", t, pagina);
        return bloco("texto", t, pagina);
    }

    static Bloco classificarVersos(List<Linha> g, int pagina) {
        if (g.size() == 1) {
            String t = g.get(0).texto;
            boolean tituloVerso = !t.contains("*") && (CABECALHO_VERSO.matcher(t).matches()
                    || (t.length() <= 45 && !FIM_FRASE.matcher(t).matches() && !Character.isDigit(t.charAt(0))));
            if (g.get(0).negrito || tituloVerso) return bloco(maiusculas(t) ? "titulo" : "subtitulo", t, pagina);
            if (g.get(0).italico) return bloco("rubrica", t, pagina);
        }
        StringBuilder sb = new StringBuilder();
        for (Linha l : g) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.texto);
        }
        return bloco("verso", sb.toString(), pagina);
    }

    static boolean cabecalhoDeVerso(String t) {
        if (t.contains("*") || t.length() > 45) return false;
        return CABECALHO_VERSO.matcher(t).matches() || t.matches(".*\\bDia: .*") || t.matches("^Livro [IVX]+$");
    }

    /** Colunas da página = posições iniciais da linha com mais células. */
    static List<Float> colunas(List<Linha> ls) {
        List<Float> melhor = new ArrayList<>();
        for (Linha l : ls) if (l.inicios.size() >= 2 && l.inicios.size() > melhor.size()) melhor = l.inicios;
        return melhor;
    }

    static int colunaDe(List<Float> cols, float x) {
        int c = 0;
        for (int i = 0; i < cols.size(); i++) if (cols.get(i) <= x + 8f) c = i;
        return c;
    }

    static List<Bloco> blocosDaPagina(List<Linha> ls, String modo, int pagina) {
        List<Bloco> out = new ArrayList<>();
        switch (modo) {
            case "versos" -> {
                for (List<Linha> g : agrupar(ls, 2.0f)) {
                    int i = 0;   // títulos de salmo colados ao primeiro verso viram subtítulos
                    while (i < g.size() - 1 && cabecalhoDeVerso(g.get(i).texto)) {
                        out.add(bloco("subtitulo", g.get(i).texto, pagina));
                        i++;
                    }
                    out.add(classificarVersos(g.subList(i, g.size()), pagina));
                }
            }
            case "tabela" -> {
                List<Float> cols = colunas(ls);
                Bloco tabela = null;
                for (List<Linha> g : agrupar(ls, 1.45f)) {
                    boolean multi = g.stream().anyMatch(l -> l.celulas.size() >= 2);
                    boolean linhaCurta = cols.size() >= 2 && g.stream().allMatch(l -> l.celulas.size() == 1
                            && l.inicios.get(0) <= cols.get(0) + 8f && l.texto.length() <= 40)
                            && !maiusculas(g.get(0).texto);
                    if (cols.size() >= 2 && (multi || linhaCurta)) {
                        List<StringBuilder> cel = new ArrayList<>();
                        for (int i = 0; i < cols.size(); i++) cel.add(new StringBuilder());
                        for (Linha l : g) {
                            for (int k = 0; k < l.celulas.size(); k++) {
                                StringBuilder sb = cel.get(colunaDe(cols, l.inicios.get(k)));
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(l.celulas.get(k));
                            }
                        }
                        List<String> linha = new ArrayList<>();
                        for (StringBuilder sb : cel) linha.add(sb.toString());
                        if (tabela == null) {
                            tabela = bloco("tabela", null, pagina);
                            tabela.linhas = new ArrayList<>();
                            tabela.cabecalho = todas(g, true);
                            out.add(tabela);
                        }
                        tabela.linhas.add(linha);
                    } else {
                        tabela = null;
                        out.add(classificarProsa(g, pagina));
                    }
                }
            }
            default -> {
                for (List<Linha> g : agrupar(ls, 1.45f)) out.add(classificarProsa(g, pagina));
            }
        }
        return out;
    }

    /** Une parágrafo que continua na página seguinte. */
    static void anexar(List<Bloco> acumulado, List<Bloco> novos, String modo) {
        if (!novos.isEmpty() && !acumulado.isEmpty() && modo.equals("prosa")) {
            Bloco ant = acumulado.get(acumulado.size() - 1);
            Bloco prim = novos.get(0);
            boolean mesmoTipo = ant.tipo.equals(prim.tipo) && (ant.tipo.equals("texto") || ant.tipo.equals("rubrica"));
            if (mesmoTipo && !FIM_FRASE.matcher(ant.texto).matches()
                    && !prim.texto.isEmpty() && Character.isLowerCase(prim.texto.charAt(0))) {
                ant.texto = ant.texto + " " + prim.texto;
                novos.remove(0);
            }
        }
        acumulado.addAll(novos);
    }

    // ---------------------------------------------------------------------- JSON

    static String esc(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    static String jsonBloco(Bloco b) {
        StringBuilder sb = new StringBuilder("    {\"tipo\":").append(esc(b.tipo)).append(",\"pagina\":").append(b.pagina);
        if (b.linhas != null) {
            sb.append(",\"cabecalho\":").append(b.cabecalho).append(",\"linhas\":[");
            for (int i = 0; i < b.linhas.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('[');
                for (int j = 0; j < b.linhas.get(i).size(); j++) {
                    if (j > 0) sb.append(',');
                    sb.append(esc(b.linhas.get(i).get(j)));
                }
                sb.append(']');
            }
            sb.append(']');
        } else {
            sb.append(",\"texto\":").append(esc(b.texto));
        }
        return sb.append('}').toString();
    }

    static String jsonSecao(Secao s, List<Bloco> blocos) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"id\":").append(esc(s.id())).append(",\n");
        sb.append("  \"titulo\":").append(esc(s.titulo())).append(",\n");
        sb.append("  \"modo\":").append(esc(s.modo())).append(",\n");
        sb.append("  \"paginaInicial\":").append(s.inicio()).append(",\n");
        sb.append("  \"paginaFinal\":").append(s.fim()).append(",\n");
        sb.append("  \"blocos\":[\n");
        for (int i = 0; i < blocos.size(); i++) {
            sb.append(jsonBloco(blocos.get(i)));
            sb.append(i < blocos.size() - 1 ? ",\n" : "\n");
        }
        return sb.append("  ]\n}\n").toString();
    }

    // ------------------------------------------------------------------ programa

    static String slug(String t) {
        String s = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static List<Secao> lerSecoes(Path arquivo, int totalPaginas) throws IOException {
        List<String[]> linhas = new ArrayList<>();
        for (String l : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
            l = l.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split(";", 3);
            if (p.length < 3) throw new IOException("Linha inválida em " + arquivo + ": " + l);
            linhas.add(new String[]{p[0].strip(), p[1].strip().toLowerCase(Locale.ROOT), p[2].strip()});
        }
        List<Secao> out = new ArrayList<>();
        int ordem = 0;
        for (int i = 0; i < linhas.size(); i++) {
            int ini = Integer.parseInt(linhas.get(i)[0]);
            int fim = i + 1 < linhas.size() ? Integer.parseInt(linhas.get(i + 1)[0]) - 1 : totalPaginas;
            if (!linhas.get(i)[1].equals("pular")) ordem++;
            String id = String.format("%02d-%s", ordem, slug(linhas.get(i)[2]));
            out.add(new Secao(id, linhas.get(i)[2], linhas.get(i)[1], ini, fim));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: java -jar loc-conversor.jar livro.pdf docs/data [secoes.txt]");
            System.exit(1);
        }
        File pdf = new File(args[0]);
        Path saida = Path.of(args[1]);
        Path secoesTxt = Path.of(args.length > 2 ? args[2] : "secoes.txt");
        Files.createDirectories(saida.resolve("secoes"));

        try (PDDocument doc = PDDocument.load(pdf)) {
            List<Secao> secoes = lerSecoes(secoesTxt, doc.getNumberOfPages());
            Extrator ext = new Extrator();
            StringBuilder indice = new StringBuilder("[\n");
            boolean primeira = true;

            for (Secao s : secoes) {
                if (s.modo().equals("pular")) continue;
                List<Bloco> blocos = new ArrayList<>();
                for (int p = s.inicio(); p <= s.fim(); p++) {
                    List<Linha> ls = ext.extrair(doc, p);
                    removerRodape(ls, p);
                    anexar(blocos, blocosDaPagina(ls, s.modo(), p), s.modo());
                }
                Files.writeString(saida.resolve("secoes").resolve(s.id() + ".json"), jsonSecao(s, blocos), StandardCharsets.UTF_8);
                if (!primeira) indice.append(",\n");
                primeira = false;
                indice.append("  {\"id\":").append(esc(s.id())).append(",\"titulo\":").append(esc(s.titulo()))
                      .append(",\"paginaInicial\":").append(s.inicio()).append(",\"paginaFinal\":").append(s.fim()).append('}');
                System.out.printf("%-48s p.%d–%d  %d blocos%n", s.titulo(), s.inicio(), s.fim(), blocos.size());
            }
            indice.append("\n]\n");
            Files.writeString(saida.resolve("indice.json"), indice.toString(), StandardCharsets.UTF_8);
        }
        System.out.println("Pronto. Arquivos em " + saida.toAbsolutePath());
    }
}
