package io.freemytv;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Plain-Java tests for Reader: sh tests/run.sh [saved feed.xml] [folder of saved article pages] */
public final class ReaderTest {
    public static void main(String[] args) throws Exception {
        String lorem = "Este é um parágrafo de verdade da matéria, longo o bastante para contar como texto.";
        String html = "<html><head><meta property=\"og:title\" content=\"Chuva forte &amp; ventos atingem Maringá\">"
                + "<meta property=\"og:site_name\" content=\"g1\"><meta property=\"og:image\" content=\"https://img.example/hero.jpg\">"
                + "<script>var x = '<p>" + lorem + " script</p>';</script></head><body>"
                + "<nav><p>" + lorem + " menu</p></nav>"
                + "<p>Assine a newsletter e receba as notícias.</p>"
                + "<article><figure><img src=\"https://img.example/lead.jpg\" width=\"1200\"></figure>"
                + "<p>" + lorem + " Um.</p><p>" + lorem + " <a href=\"x\">Dois</a>&nbsp;&eacute;.</p>"
                + "<img src=\"/logo.png\"><img data-src=\"//cdn.example/foto2.jpg\" src=\"data:image/gif;base64,AA\">"
                + "<h2>O que dizem os moradores</h2><p>" + lorem + " Três.</p><p>" + lorem + " Três.</p>"
                + "<p>Curto.</p></article>"
                + "<div class=\"related\"><p>Outra matéria</p></div></body></html>";
        Reader.Article a = Reader.extract(html, "https://g1.globo.com/pr/noticia.ghtml");
        check(a.title.equals("Chuva forte & ventos atingem Maringá"), "title: " + a.title);
        check(a.source.equals("g1"), "source: " + a.source);
        check(a.blocks.get(0).image != null && a.blocks.get(0).image.equals("https://img.example/lead.jpg"), "lead photo first, not the share photo");
        int paragraphs = 0, images = 0, headings = 0;
        for (Reader.Block b : a.blocks) {
            if (b.image != null) images++;
            else if (b.heading) headings++;
            else paragraphs++;
            check(b.text == null || !b.text.contains("menu") && !b.text.contains("script") && !b.text.contains("Assine"), "no boilerplate: " + b.text);
        }
        check(paragraphs == 3, "paragraphs (duplicates and short ones out): " + paragraphs);
        check(images == 2, "images (lazy one in, logo out): " + images);
        check(headings == 1, "subheading: " + headings);
        check(a.blocks.get(2).text.endsWith("Dois é."), "tags out, entities in: " + a.blocks.get(2).text);
        check(a.blocks.get(3).image.equals("https://cdn.example/foto2.jpg"), "protocol-relative: " + a.blocks.get(3).image);

        // A page built by script: falls back to the declared articleBody.
        Reader.Article js = Reader.extract("<meta property=\"og:image\" content=\"https://i/x.jpg\"><script type=\"application/ld+json\">"
                + "{\"@type\":\"NewsArticle\",\"articleBody\":\"Primeiro parágrafo com \\\"aspas\\\" e acentua\\u00e7\\u00e3o.\\nSegundo.\"}</script>", "https://x/");
        check(js.blocks.size() == 3 && js.blocks.get(0).image != null, "articleBody + share photo: " + js.blocks.size());
        check(js.blocks.get(1).text.equals("Primeiro parágrafo com \"aspas\" e acentuação."), "json unescape: " + js.blocks.get(1).text);

        check(Reader.decode("&#227;&#xE7;&ccedil;&Atilde;&foo;").equals("ãççÃ&foo;"), "entities");
        check(Reader.absolute("../a.jpg", "https://s.com/x/y/z.html").equals("https://s.com/x/a.jpg"), "relative url");

        if (args.length > 1) {
            File[] pages = new File(args[1]).listFiles((d, n) -> n.endsWith(".html"));
            java.util.Arrays.sort(pages);
            int good = 0;
            for (File f : pages) {
                String page = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                long t0 = System.nanoTime();
                Reader.Article r = Reader.extract(page, "https://" + f.getName().replace(".html", "") + "/");
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int img = 0;
                for (Reader.Block b : r.blocks) if (b.image != null) img++;
                String firstText = "";
                for (Reader.Block b : r.blocks) if (b.text != null) { firstText = b.text; break; }
                if (r.textLength() > 600) good++;
                System.out.printf("  %-26s %5d chars %2d imgs %4d ms | %.70s%n", f.getName().replace(".html", ""), r.textLength(), img, ms, firstText);
            }
            System.out.println("  " + good + "/" + pages.length + " pages with more than 600 characters of text");
        }
        System.out.println("ReaderTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
