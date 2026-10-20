package io.freemytv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A news article as text and images, for reading on the TV: resolves the Google News link to the site's own URL,
 * downloads the page and keeps the article's paragraphs, subheadings and photos. No DOM: the article is the longest
 * run of real paragraphs in the page. Plain Java, so it runs in tests off the TV.
 */
final class Reader {
    private Reader() {}

    static final String UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int MAX_PAGE = 4 << 20, MAX_IMAGES = 8;
    /** Two paragraphs further apart than this (in characters of HTML) belong to different parts of the page. */
    private static final int GAP = 20_000;

    static final class Block {
        final String text, image;
        final boolean heading;
        Block(String text, String image, boolean heading) { this.text = text; this.image = image; this.heading = heading; }
    }

    static final class Article {
        String title = "", source = "", url = "";
        final List<Block> blocks = new ArrayList<>();
        int textLength() {
            int n = 0;
            for (Block b : blocks) if (b.text != null) n += b.text.length();
            return n;
        }
    }

    // MARK: - Network

    /** Downloads and extracts the article behind a feed link (a Google News link or the site's own). */
    static Article load(String link) throws IOException {
        String url = link.contains("news.google.com/") ? resolveGoogleNews(link) : link;
        Article a = extract(fetch(url, null), url);
        a.url = url;
        return a;
    }

    /**
     * Google News links don't redirect anymore: the article's page carries a signature and a timestamp, and Google's
     * own batchexecute call trades them for the site's URL (what the Google News web app does).
     */
    static String resolveGoogleNews(String link) throws IOException {
        Matcher id = Pattern.compile("articles/([^?/]+)").matcher(link);
        if (!id.find()) throw new IOException("not a Google News link");
        String gid = id.group(1);
        String page = fetch("https://news.google.com/rss/articles/" + gid, null);
        String sg = first(page, "data-n-a-sg=\"([^\"]+)\""), ts = first(page, "data-n-a-ts=\"([^\"]+)\"");
        if (sg == null || ts == null) throw new IOException("Google News page without signature");
        String inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1],"
                + "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"" + gid + "\"," + ts + ",\"" + sg + "\"]";
        String req = "[[[\"Fbv4je\"," + jsonString(inner) + ",null,\"generic\"]]]";
        String body = "f.req=" + URLEncoder.encode(req, "UTF-8");
        String reply = fetch("https://news.google.com/_/DotsSplashUi/data/batchexecute", body);
        String url = first(reply, "garturlres\\\\\",\\\\\"(https?://[^\\\\\"]+)");
        if (url == null) throw new IOException("Google News did not resolve the link");
        return url;
    }

    static String fetch(String url, String post) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
        try {
            if (post != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                try (OutputStream out = c.getOutputStream()) { out.write(post.getBytes(StandardCharsets.UTF_8)); }
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < MAX_PAGE) out.write(buffer, 0, n);
                bytes = out.toByteArray();
            }
            return new String(bytes, charset(c.getContentType(), bytes));
        } finally {
            c.disconnect();
        }
    }

    private static Charset charset(String contentType, byte[] bytes) {
        String name = contentType == null ? null : first(contentType, "charset=([\\w-]+)");
        if (name == null) {
            String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
            name = first(head, "(?i)<meta[^>]+charset=[\"']?([\\w-]+)");
        }
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    // MARK: - Extraction

    private static final Pattern DROP = Pattern.compile(
            "(?is)<!--.*?-->|<(script|style|noscript|svg|nav|footer|aside|form|button|select|iframe)\\b.*?</\\1\\s*>");
    private static final Pattern PIECES = Pattern.compile(
            "(?is)<p\\b[^>]*>(.*?)</p\\s*>|<h([23])\\b[^>]*>(.*?)</h\\2\\s*>|<(?:amp-)?img\\b([^>]*)>");
    private static final Pattern BOILERPLATE = Pattern.compile(
            "(?i)cookies|assine|assinante|newsletter|todos os direitos|leia também|leia mais|siga o .* no|"
                    + "clique aqui|baixe o app|inscreva-se|publicidade|copyright|©");

    static Article extract(String html, String url) {
        Article a = new Article();
        a.title = clean(meta(html, "og:title"));
        if (a.title.isEmpty()) a.title = clean(first(html, "(?is)<title[^>]*>(.*?)</title>"));
        a.source = clean(meta(html, "og:site_name"));
        String hero = absolute(meta(html, "og:image"), url);
        String jsonBody = jsonField(html, "articleBody");
        String body = DROP.matcher(html).replaceAll(" ");

        // Every paragraph, subheading and image, in page order, with where it starts.
        List<int[]> spans = new ArrayList<>();
        List<Block> found = new ArrayList<>();
        Matcher m = PIECES.matcher(body);
        while (m.find()) {
            Block b = null;
            if (m.group(1) != null) {
                String t = clean(m.group(1));
                if (paragraph(t)) b = new Block(t, null, false);
            } else if (m.group(3) != null) {
                String t = clean(m.group(3));
                if (t.length() >= 8 && t.length() <= 160) b = new Block(t, null, true);
            } else {
                String src = image(m.group(4), url);
                if (src != null) b = new Block(null, src, false);
            }
            if (b != null) {
                found.add(b);
                spans.add(new int[] {m.start(), b.text != null && !b.heading ? b.text.length() : 0});
            }
        }

        // The article: the run of paragraphs, close to each other, with the most text.
        int bestFrom = -1, bestTo = -1, bestText = 0, from = -1, text = 0, lastAt = -GAP - 1;
        for (int i = 0; i < found.size(); i++) {
            int[] s = spans.get(i);
            if (s[1] == 0) continue;
            if (s[0] - lastAt > GAP) {
                from = i;
                text = 0;
            }
            text += s[1];
            lastAt = s[0];
            if (text > bestText) {
                bestText = text;
                bestFrom = from;
                bestTo = i;
            }
        }

        Set<String> seen = new HashSet<>();
        int images = 0;
        if (bestFrom >= 0) {
            // Include a photo just above the first paragraph (the lead photo usually sits there).
            int start = bestFrom;
            while (start > 0 && found.get(start - 1).image != null && spans.get(bestFrom)[0] - spans.get(start - 1)[0] < GAP / 4) start--;
            for (int i = start; i <= bestTo; i++) {
                Block b = found.get(i);
                if (b.image != null) {
                    if (images >= MAX_IMAGES || !seen.add(b.image)) continue;
                    images++;
                } else if (!seen.add(b.text)) {
                    continue;
                }
                a.blocks.add(b);
            }
        }
        // Too little text there (a page built by script): the article body the page declares for search engines.
        if (a.textLength() < 400 && jsonBody != null) {
            a.blocks.clear();
            for (String p : unescapeJson(jsonBody).split("\\n+")) {
                String t = clean(p);
                if (!t.isEmpty()) a.blocks.add(new Block(t, null, false));
            }
        }
        // The share photo on top, when the article has none of its own near the start.
        boolean photoFirst = false;
        for (int i = 0; i < Math.min(3, a.blocks.size()); i++) if (a.blocks.get(i).image != null) photoFirst = true;
        if (!photoFirst && hero != null && !seen.contains(hero)) a.blocks.add(0, new Block(null, hero, false));
        return a;
    }

    private static boolean paragraph(String t) {
        if (t.length() < 30) return false;
        if (t.length() < 60 && !t.matches(".*[.!?:\"”]$")) return false;
        return !(t.length() < 200 && BOILERPLATE.matcher(t).find());
    }

    private static String image(String attrs, String base) {
        String src = attr(attrs, "data-src");
        if (src == null) src = attr(attrs, "data-lazy-src");
        if (src == null) src = attr(attrs, "src");
        if (src == null || src.startsWith("data:")) {
            String set = attr(attrs, "srcset");
            if (set == null) set = attr(attrs, "data-srcset");
            if (set != null) src = set.trim().split("[\\s,]+")[0];
        }
        if (src == null || src.startsWith("data:")) return null;
        String lower = src.toLowerCase(Locale.ROOT);
        if (lower.contains(".svg") || lower.contains(".gif") || lower.matches(".*(logo|avatar|icon|pixel|sprite|placeholder|tracking).*")) return null;
        String width = attr(attrs, "width");
        if (width != null && width.matches("\\d+") && Integer.parseInt(width) < 300) return null;
        return absolute(decode(src), base);
    }

    private static String attr(String attrs, String name) {
        return first(attrs, "(?i)(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*[\"']([^\"']*)[\"']");
    }

    private static String meta(String html, String property) {
        String q = Pattern.quote(property);
        String v = first(html, "(?i)<meta[^>]+(?:property|name)=[\"']" + q + "[\"'][^>]*content=[\"']([^\"']*)[\"']");
        return v != null ? v : first(html, "(?i)<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + q + "[\"']");
    }

    static String absolute(String src, String base) {
        if (src == null || src.trim().isEmpty()) return null;
        src = src.trim();
        if (src.startsWith("//")) return "https:" + src;
        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        try {
            return new URL(new URL(base), src).toString();
        } catch (Exception e) {
            return null;
        }
    }

    // MARK: - Text

    /** Tags out, entities decoded, white space collapsed. */
    static String clean(String html) {
        if (html == null) return "";
        String t = html.replaceAll("(?is)<br\\s*/?>", " ").replaceAll("(?s)<[^>]*>", "");
        return decode(t).replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static final Map<String, String> ENTITIES = new HashMap<>();
    static {
        String[] list = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ", "ndash", "–",
                "mdash", "—", "hellip", "…", "laquo", "«", "raquo", "»", "ldquo", "“", "rdquo", "”", "lsquo", "‘",
                "rsquo", "’", "ordm", "º", "ordf", "ª", "deg", "°", "middot", "·", "bull", "•", "euro", "€", "copy", "©"};
        for (int i = 0; i < list.length; i += 2) ENTITIES.put(list[i], list[i + 1]);
        String letters = "aAeEiIoOuU";
        String[][] accents = {{"acute", "áÁéÉíÍóÓúÚ"}, {"grave", "àÀèÈìÌòÒùÙ"}, {"circ", "âÂêÊîÎôÔûÛ"}, {"uml", "äÄëËïÏöÖüÜ"}};
        for (String[] kind : accents) {
            for (int i = 0; i < letters.length(); i++) ENTITIES.put(letters.charAt(i) + kind[0], String.valueOf(kind[1].charAt(i)));
        }
        ENTITIES.put("atilde", "ã"); ENTITIES.put("Atilde", "Ã"); ENTITIES.put("otilde", "õ"); ENTITIES.put("Otilde", "Õ");
        ENTITIES.put("ntilde", "ñ"); ENTITIES.put("Ntilde", "Ñ"); ENTITIES.put("ccedil", "ç"); ENTITIES.put("Ccedil", "Ç");
    }
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");

    static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                if (e.startsWith("#x")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else r = ENTITIES.get(e);
            } catch (Exception ex) {
                r = null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r != null ? r : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String unescapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) { out.append(ch); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': out.append('\n'); break;
                case 'r': break;
                case 't': out.append(' '); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            out.append('u');
                        }
                    }
                    break;
                default: out.append(n);
            }
        }
        return out.toString();
    }

    /** The raw (still escaped) value of the first "name": "..." in the page. A scan: a regex overflows the stack here. */
    static String jsonField(String html, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"").matcher(html);
        if (!m.find()) return null;
        int i = m.end();
        StringBuilder out = new StringBuilder();
        while (i < html.length()) {
            char ch = html.charAt(i++);
            if (ch == '"') return out.toString();
            out.append(ch);
            if (ch == '\\' && i < html.length()) out.append(html.charAt(i++));
        }
        return null;
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String first(String s, String regex) {
        if (s == null) return null;
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
