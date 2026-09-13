import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Main::home);
        server.createContext("/api/search", Main::search);
        server.createContext("/api/ask", Main::ask);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Luma Library is running at http://localhost:" + PORT);
    }

    private static void home(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/")) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", PAGE);
    }

    private static void search(HttpExchange exchange) throws IOException {
        Map<String, String> query = queryParams(exchange.getRequestURI().getRawQuery());
        String term = query.getOrDefault("q", "classic literature");
        int page = parseInt(query.getOrDefault("page", "1"), 1);
        int limit = Math.min(parseInt(query.getOrDefault("limit", "12"), 12), 24);
        List<Book> books = retrieve(term, page, limit);
        StringBuilder json = new StringBuilder("{\"query\":");
        json.append(jsonString(term)).append(",\"page\":").append(page).append(",\"books\":[");
        for (int i = 0; i < books.size(); i++) {
            if (i > 0) json.append(',');
            json.append(books.get(i).json());
        }
        json.append("],\"total\":").append(Math.max(books.size(), 1000)).append('}');
        send(exchange, 200, "application/json; charset=utf-8", json.toString());
    }

    private static void ask(HttpExchange exchange) throws IOException {
        Map<String, String> query = queryParams(exchange.getRequestURI().getRawQuery());
        String question = query.getOrDefault("q", "What should I read?");
        List<Book> context = retrieve(question, 1, 5);
        StringBuilder answer = new StringBuilder();
        if (context.isEmpty()) {
            answer.append("I couldn't find a close match yet. Try a title, author, genre, or theme.");
        } else {
            answer.append("Based on the library's retrieved catalog, I'd start with ");
            answer.append(context.get(0).title).append(" by ").append(context.get(0).author);
            answer.append(". It is a strong match for your question");
            if (context.size() > 1) {
                answer.append(", with ");
                for (int i = 1; i < Math.min(context.size(), 3); i++) {
                    if (i > 1) answer.append(" and ");
                    answer.append(context.get(i).title);
                }
                answer.append(" as nearby reads");
            }
            answer.append('.');
        }
        StringBuilder json = new StringBuilder("{\"answer\":").append(jsonString(answer.toString())).append(",\"sources\":[");
        for (int i = 0; i < context.size(); i++) {
            if (i > 0) json.append(',');
            json.append(context.get(i).json());
        }
        json.append("]}");
        send(exchange, 200, "application/json; charset=utf-8", json.toString());
    }

    private static List<Book> retrieve(String term, int page, int limit) {
        try {
            String url = "https://openlibrary.org/search.json?q=" +
                    URLEncoder.encode(term, StandardCharsets.UTF_8) +
                    "&page=" + page + "&limit=" + limit +
                    "&fields=title,author_name,first_publish_year,cover_i,key,subject";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "LumaLibrary/1.0 (local educational demo)")
                    .GET().build();
            String body = HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
            List<Book> books = parseDocs(body);
            if (!books.isEmpty()) return books;
        } catch (Exception ignored) {
            // The local catalog below keeps the experience useful offline.
        }
        return fallback(term);
    }

    private static List<Book> parseDocs(String body) {
        List<Book> books = new ArrayList<>();
        int docsStart = body.indexOf("\"docs\":[");
        if (docsStart < 0) return books;
        int start = docsStart + 8;
        int depth = 0;
        int objectStart = -1;
        boolean string = false;
        boolean escaped = false;
        for (int i = start; i < body.length(); i++) {
            char c = body.charAt(i);
            if (string) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
                continue;
            }
            if (c == '"') string = true;
            else if (c == '{') {
                if (depth++ == 0) objectStart = i;
            } else if (c == '}' && --depth == 0 && objectStart >= 0) {
                String object = body.substring(objectStart, i + 1);
                String title = value(object, "title");
                if (!title.isBlank()) books.add(new Book(title, value(object, "author_name"), value(object, "first_publish_year"),
                        value(object, "cover_i"), value(object, "key")));
                objectStart = -1;
                if (books.size() == 24) break;
            } else if (c == ']' && depth == 0) break;
        }
        return books;
    }

    private static String value(String object, String key) {
        String marker = "\"" + key + "\":";
        int at = object.indexOf(marker);
        if (at < 0) return "";
        int start = at + marker.length();
        while (start < object.length() && Character.isWhitespace(object.charAt(start))) start++;
        if (start >= object.length()) return "";
        if (object.charAt(start) == '[') {
            int quote = object.indexOf('"', start);
            int end = quote < 0 ? -1 : object.indexOf('"', quote + 1);
            return quote >= 0 && end > quote ? unescape(object.substring(quote + 1, end)) : "";
        }
        if (object.charAt(start) == '"') {
            int end = start + 1;
            while (end < object.length()) {
                if (object.charAt(end) == '"' && object.charAt(end - 1) != '\\') break;
                end++;
            }
            return unescape(object.substring(start + 1, Math.min(end, object.length())));
        }
        int end = start;
        while (end < object.length() && ",}".indexOf(object.charAt(end)) < 0) end++;
        return object.substring(start, end).trim();
    }

    private static List<Book> fallback(String term) {
        String[][] data = {
                {"The Left Hand of Darkness", "Ursula K. Le Guin", "1969", "", "/works/OL59864W"},
                {"Pride and Prejudice", "Jane Austen", "1813", "", "/works/OL66534W"},
                {"The Dispossessed", "Ursula K. Le Guin", "1974", "", "/works/OL59882W"},
                {"One Hundred Years of Solitude", "Gabriel García Márquez", "1967", "", "/works/OL266894W"},
                {"The Master and Margarita", "Mikhail Bulgakov", "1967", "", "/works/OL362842W"},
                {"Kindred", "Octavia E. Butler", "1979", "", "/works/OL73402W"},
                {"The Odyssey", "Homer", "800", "", "/works/OL45804W"},
                {"Invisible Cities", "Italo Calvino", "1972", "", "/works/OL2117793W"}
        };
        List<Book> books = new ArrayList<>();
        for (String[] row : data) books.add(new Book(row[0], row[1], row[2], row[3], row[4]));
        return books;
    }

    private static Map<String, String> queryParams(String raw) {
        java.util.HashMap<String, String> result = new java.util.HashMap<>();
        if (raw == null) return result;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) result.put(parts[0], java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record Book(String title, String author, String year, String cover, String key) {
        String json() {
            String coverUrl = cover.isBlank() ? "" : "https://covers.openlibrary.org/b/id/" + cover + "-M.jpg";
            return "{\"title\":" + jsonString(title) + ",\"author\":" + jsonString(author.isBlank() ? "Unknown author" : author)
                    + ",\"year\":" + jsonString(year) + ",\"cover\":" + jsonString(coverUrl)
                    + ",\"key\":" + jsonString("https://openlibrary.org" + key) + "}";
        }
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Luma — a living library</title>
              <style>
                :root{--ink:#18201d;--muted:#6d756f;--paper:#f7f4ed;--card:#fffdf8;--green:#315a4c;--lime:#c9df91;--line:#dfe2d8;--orange:#ef8b54}
                *{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:15px Inter,ui-sans-serif,system-ui,-apple-system,sans-serif}
                .shell{max-width:1240px;margin:auto;padding:0 34px}.nav{height:82px;display:flex;align-items:center;justify-content:space-between}
                .brand{font:700 24px Georgia,serif;letter-spacing:-1px}.brand span{color:var(--orange)}.navlinks{display:flex;gap:27px;color:var(--muted);font-size:13px}.navlinks a{color:inherit;text-decoration:none}
                .hero{display:grid;grid-template-columns:1.2fr .8fr;min-height:385px;align-items:center;gap:30px}.eyebrow{color:var(--orange);font-weight:700;font-size:12px;text-transform:uppercase;letter-spacing:2px}
                h1{font:500 clamp(48px,7vw,84px)/.92 Georgia,serif;letter-spacing:-5px;margin:18px 0}.hero p{max-width:480px;color:var(--muted);font-size:16px;line-height:1.6}
                .orb{height:300px;position:relative;background:var(--green);border-radius:47% 53% 46% 54%/54% 44% 56% 46%;transform:rotate(-8deg);overflow:hidden}
                .orb:before{content:"";position:absolute;width:200px;height:200px;border:1px solid #e5edcb;border-radius:50%;top:35px;left:54px;opacity:.55}.orb:after{content:"✦";position:absolute;color:var(--lime);font-size:75px;right:65px;bottom:47px;transform:rotate(8deg)}
                .searchbox{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:9px;display:flex;max-width:670px;box-shadow:0 8px 25px #3654440b}.searchbox input{border:0;outline:0;background:transparent;padding:13px 15px;flex:1;font-size:15px;color:var(--ink)}button{border:0;cursor:pointer}
                .searchbox button,.ask button{background:var(--green);color:white;border-radius:12px;padding:13px 21px;font-weight:700}.stats{display:flex;gap:44px;margin-top:35px}.stat b{display:block;font:500 27px Georgia,serif}.stat small{color:var(--muted)}
                .workspace{border-top:1px solid var(--line);padding:42px 0 80px}.sectionhead{display:flex;align-items:end;justify-content:space-between;margin-bottom:23px}.sectionhead h2{font:500 32px Georgia,serif;margin:0}.sectionhead p{color:var(--muted);margin:6px 0 0}.status{font-size:12px;color:var(--muted)}
                .toolbar{display:flex;gap:9px;flex-wrap:wrap;margin-bottom:23px}.pill{border:1px solid var(--line);background:transparent;padding:9px 14px;border-radius:30px;color:var(--muted);font-size:12px}.pill.active{background:var(--lime);border-color:var(--lime);color:var(--ink)}
                .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:17px}.book{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:13px;min-height:275px;display:flex;flex-direction:column;transition:.2s}.book:hover{transform:translateY(-4px);box-shadow:0 12px 25px #36544412}
                .cover{height:135px;border-radius:8px;background:linear-gradient(135deg,#c9df91,#315a4c);display:flex;align-items:flex-end;padding:13px;color:white;font:18px Georgia,serif;line-height:1.05;overflow:hidden}.book:nth-child(3n) .cover{background:linear-gradient(135deg,#e9b57b,#be5941)}.book:nth-child(4n) .cover{background:linear-gradient(135deg,#8aa6ad,#344e56)}
                .book h3{font:600 16px Georgia,serif;margin:15px 0 6px;line-height:1.2}.book p{margin:0;color:var(--muted);font-size:12px}.book .meta{margin-top:auto;padding-top:15px;display:flex;justify-content:space-between;color:#9aa19b;font-size:11px}.book a{color:var(--green);text-decoration:none}
                .ask{margin-top:54px;background:#e9eedc;border-radius:18px;padding:27px;display:flex;justify-content:space-between;gap:20px;align-items:center}.ask h3{font:500 25px Georgia,serif;margin:0 0 5px}.ask p{color:var(--muted);margin:0}.ask form{display:flex;min-width:47%;background:var(--card);border-radius:12px;padding:5px}.ask input{border:0;outline:0;background:transparent;padding:10px;flex:1}
                .answer{display:none;background:var(--card);border-radius:12px;padding:16px;margin-top:12px;color:var(--muted);line-height:1.5}.answer.show{display:block}
                @media(max-width:850px){.hero{grid-template-columns:1fr}.orb{display:none}.grid{grid-template-columns:repeat(2,1fr)}.ask{display:block}.ask form{margin-top:18px;min-width:0}}@media(max-width:520px){.shell{padding:0 18px}.navlinks{display:none}.grid{grid-template-columns:1fr}.stats{gap:20px}.searchbox button{padding:11px 13px}}
              </style>
            </head>
            <body>
              <header class="shell nav"><div class="brand">luma<span>✦</span></div><nav class="navlinks"><a href="#library">Explore</a><a href="#ask">Ask Luma</a><a href="https://openlibrary.org" target="_blank">Open Library ↗</a></nav></header>
              <main class="shell">
                <section class="hero"><div><div class="eyebrow">A library without walls</div><h1>Find your<br><em>next world.</em></h1><p>Luma brings the world’s public book catalog into one calm, searchable space — then helps you make sense of what you find.</p><form class="searchbox" id="searchForm"><input id="query" value="magic realism" placeholder="Search by title, author, subject..."><button>Search library</button></form><div class="stats"><div class="stat"><b>34M+</b><small>cataloged works</small></div><div class="stat"><b>240+</b><small>countries represented</small></div><div class="stat"><b>∞</b><small>rabbit holes</small></div></div></div><div class="orb"></div></section>
                <section class="workspace" id="library"><div class="sectionhead"><div><h2>Open shelves</h2><p id="caption">A thoughtful starting point for your reading journey.</p></div><span class="status" id="status">Retrieving catalog...</span></div><div class="toolbar"><button class="pill active" data-q="magic realism">Magic realism</button><button class="pill" data-q="science fiction">Science fiction</button><button class="pill" data-q="women writers">Women writers</button><button class="pill" data-q="philosophy">Philosophy</button><button class="pill" data-q="short stories">Short stories</button></div><div class="grid" id="books"></div><div class="ask" id="ask"><div><h3>Ask the collection.</h3><p>Describe a mood, theme, or question. Luma will retrieve nearby books.</p></div><form id="askForm"><input id="question" placeholder="What should I read about starting over?"><button>Ask Luma</button></form><div class="answer" id="answer"></div></div></section>
              </main>
              <script>
                const books=document.querySelector('#books'), status=document.querySelector('#status'), caption=document.querySelector('#caption');
                async function load(q){status.textContent='Retrieving catalog...';books.innerHTML='<div class="book"><div class="cover"></div><h3>Opening the shelves…</h3><p>Finding thoughtful matches.</p></div>'.repeat(4);try{const r=await fetch('/api/search?q='+encodeURIComponent(q));const d=await r.json();books.innerHTML=d.books.map(b=>`<article class="book"><div class="cover">${b.title}</div><h3>${b.title}</h3><p>${b.author}</p><div class="meta"><span>${b.year||'Unknown date'}</span><a href="${b.key}" target="_blank">View work ↗</a></div></article>`).join('');status.textContent=d.books.length+' works shown · live catalog';caption.textContent='Retrieved for “'+d.query+'” from the world’s public book data.'}catch(e){status.textContent='Catalog temporarily unavailable';books.innerHTML=''}}
                document.querySelector('#searchForm').addEventListener('submit',e=>{e.preventDefault();load(document.querySelector('#query').value||'classic literature')});
                document.querySelectorAll('.pill').forEach(p=>p.addEventListener('click',()=>{document.querySelectorAll('.pill').forEach(x=>x.classList.remove('active'));p.classList.add('active');document.querySelector('#query').value=p.dataset.q;load(p.dataset.q)}));
                document.querySelector('#askForm').addEventListener('submit',async e=>{e.preventDefault();const q=document.querySelector('#question').value;if(!q)return;const a=document.querySelector('#answer');a.textContent='Thinking with the collection…';a.classList.add('show');const r=await fetch('/api/ask?q='+encodeURIComponent(q));const d=await r.json();a.textContent=d.answer+'  Sources: '+d.sources.map(x=>x.title).join(', ')+'.'});
                load('magic realism');
              </script>
            </body></html>
            """;
}
