package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities.EscapeMode;
import org.jsoup.select.Elements;

import es.tododev.ebooks.BookData.ResourceItem;

public class HtmlProcessor implements Processor {

    private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
    private static final String BOOKS_FOLDER = "books/";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Client httpClient;
    private final String isbn;
    private final String baseUrl;
    private final Map<String, String> httpHeaders;
    private String bookFolder;

    public HtmlProcessor(String baseUrl, String isbn, Map<String, String> httpHeaders) {
        this.baseUrl = baseUrl;
        this.isbn = isbn;
        this.httpHeaders = httpHeaders;
        this.httpClient = ClientBuilder.newBuilder().build();
    }

    @Override
    public void execute() throws Exception {
        BookData book = new BookData(httpClient, baseUrl, isbn, httpHeaders);
        book.fetch();
        bookFolder = BOOKS_FOLDER + isbn + "-" + book.getBookName();
        File html = new File(BOOKS_FOLDER + isbn + "-" + book.getBookName() + ".html");
        download(baseUrl + "/files/public/epub-reader/override_v1.css");
        download(baseUrl + "/library/view/dist/orm.bb9f0f2cd05444f089bc.css");
        download(baseUrl + "/library/view/dist/main.5cf5ecffc5bed2a332c4.css");
        createFiles(html, book);
        System.out.println("Generated " + html.getAbsolutePath());
    }

    private void createFiles(File html, BookData data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(html, true);
                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            bos.write(data.getCoverPage().content);
            for (ResourceItem item : data.getResources()) {
                if ("chapter".equals(item.kind)) {
                    bos.write(item.content);
                }
            }
        }
        resolveLinks(html, data);
    }
    
    private void addHeaders(Builder builder) {
        for (Entry<String, String> entry : httpHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
    }

    private void resolveLinks(File book, BookData data) throws IOException {
        Document document = Jsoup.parse(book, "UTF-8");
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        document.outputSettings().escapeMode(EscapeMode.xhtml);
        Element head = document.getElementsByTag("head").first();
        Element meta = document.createElement("meta");
        meta.attr("charset", "utf-8");
        head.appendChild(meta);
        meta = document.createElement("title");
        meta.val(data.getBookName());
        head.appendChild(meta);
        
        ResourceItem css = data.getCssResource();
        if (css != null) {
            Elements styles = document.getElementsByTag("style");
            Element style = null;
            if (styles.size() == 0) {
                style = document.createElement("style");
                head.appendChild(style);
            } else {
                style = styles.first();
            }
            style.append(new String(css.content, Charset.forName("UTF-8")));
        } else {
            System.out.println("WARNING: CSS was not found");
        }
        
        for (Element element : document.getElementsByAttribute("src")) {
            String mediaFile = element.attr("src");
            String[] file = mediaFile.split("/");
            String fileName = file[file.length - 1];
            ResourceItem media = data.getMedia().get(fileName);
            if (media != null) {
                String base64 = Base64.getEncoder().encodeToString(media.content);
                element.attr("src", "data:image/png;base64," + base64);
            } else {
                System.out.println("WARNING: " + mediaFile + " was not found");
            }
        }
        try (FileOutputStream fos = new FileOutputStream(book);
                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            bos.write(document.outerHtml().getBytes(UTF_8));
        }
    }

    private void download(String pageUrl) throws IOException {
        String filePath = safeFileName(bookFolder + pageUrl.replaceFirst(baseUrl, ""));
        File resource = new File(filePath);
        if (!resource.exists()) {
            resource.getParentFile().mkdirs();
            resource.createNewFile();
            Builder builder = httpClient.target(pageUrl).request();
            addHeaders(builder);
            Response content = builder.get();
            InputStream input = content.readEntity(InputStream.class);
            try (FileOutputStream fos2 = new FileOutputStream(resource);
                    BufferedOutputStream bos2 = new BufferedOutputStream(fos2)) {
                bos2.write(input.readAllBytes());
                bos2.flush();
            }
        }
    }

    private String safeFileName(String original) {
        if (original.startsWith("https://")) {
            original = original.replaceAll("https://", "");
        }
        if (original.charAt(0) == '/' || original.charAt(0) == '\\') {
            original = original.substring(1);
        }
        for (String banned : BANNED_CHARACTERS_FILE) {
            original = original.replaceAll(banned, "");
        }
        return original;
    }
}
