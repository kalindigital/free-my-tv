package io.freemytv;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Plain-Java test for Rss.parse: sh tests/run.sh */
public final class RssTest {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><title>x</title>"
                + "<item><title>Chuva forte atinge o Paraná - g1</title><link>https://news.google.com/rss/articles/CBMi1?oc=5</link><source url=\"https://g1.globo.com\">g1</source></item>"
                + "<item><title>Sem fonte - Folha</title></item>"
                + "<item><title>Hífen - no meio - Poder360</title><source>Poder360</source></item>"
                + "<item><title></title></item>"
                + "<item><title>Quarta</title></item><item><title>Quinta</title></item><item><title>Sexta</title></item>"
                + "</channel></rss>";
        List<Rss.Headline> list = Rss.parse(xml, 5);
        check(list.size() == 5, "max 5, skips empty titles: " + list.size());
        check(list.get(0).title.equals("Chuva forte atinge o Paraná"), "strips source: " + list.get(0).title);
        check(list.get(0).source.equals("g1"), "source tag: " + list.get(0).source);
        check(list.get(0).link.equals("https://news.google.com/rss/articles/CBMi1?oc=5"), "link: " + list.get(0).link);
        check(list.get(1).link.isEmpty(), "no link");
        check(list.get(1).source.equals("Folha") && list.get(1).title.equals("Sem fonte"), "source from title");
        check(list.get(2).title.equals("Hífen - no meio"), "only the last dash: " + list.get(2).title);
        check(list.get(3).source.isEmpty() && list.get(3).title.equals("Quarta"), "no source");
        boolean refused;
        try {
            Rss.parse("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rss><item><title>&x;</title></item></rss>", 5);
            refused = false;
        } catch (Exception e) {
            refused = true;
        }
        check(refused, "DOCTYPE refused");
        if (args.length > 0 && !args[0].isEmpty()) {
            List<Rss.Headline> real = Rss.parse(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8), 5);
            check(real.size() == 5, "real feed: " + real.size());
            for (Rss.Headline h : real) System.out.println("  " + h.title + "  ·  " + h.source);
        }
        System.out.println("RssTest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
