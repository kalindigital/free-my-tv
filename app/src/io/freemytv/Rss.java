package io.freemytv;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/** Headlines from an RSS feed (Google News: "Title - Source"). Plain Java, so it runs in tests off the TV. */
final class Rss {
    private Rss() {}

    static final class Headline {
        final String title, source, link;
        Headline(String title, String source, String link) { this.title = title; this.source = source; this.link = link; }
    }

    static List<Headline> parse(String xml, int max) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setExpandEntityReferences(false);
        // The feed comes from the network: no DOCTYPE, no external entities (where the parser supports the switch).
        for (String feature : new String[] {"http://apache.org/xml/features/disallow-doctype-decl"}) feature(f, feature, true);
        for (String feature : new String[] {"http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) feature(f, feature, false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName("item");
        List<Headline> out = new ArrayList<>();
        for (int i = 0; i < items.getLength() && out.size() < max; i++) {
            Element item = (Element) items.item(i);
            String title = text(item, "title"), source = text(item, "source");
            if (title.isEmpty()) continue;
            // Google News appends " - Source" to the title; keep it apart.
            int dash = title.lastIndexOf(" - ");
            if (dash > 0) {
                if (source.isEmpty()) source = title.substring(dash + 3).trim();
                if (title.substring(dash + 3).trim().equals(source)) title = title.substring(0, dash).trim();
            }
            out.add(new Headline(title, source, text(item, "link")));
        }
        return out;
    }

    private static void feature(DocumentBuilderFactory f, String name, boolean on) {
        try {
            f.setFeature(name, on);
        } catch (Exception ignored) {}
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }
}
