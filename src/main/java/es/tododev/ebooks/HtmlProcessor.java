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
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities.EscapeMode;
import org.jsoup.select.Elements;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Metadata;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubWriter;

public class HtmlProcessor implements Processor {

    private static final List<String> VALID_MEDIA_TYPES = Arrays.asList("text/plain", "text/html", "text/xml",
            "text/xhtml", "application/xhtml+xml", "application/xhtml", "application/xml", "application/html");
    private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
    private static final String BOOKS_FOLDER = "books/";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Client httpClient;
    private final String isbn;
    private final String baseUrl;
    private final Map<String, String> httpHeaders;
    private String bookName;
    private String bookFolder;

    public HtmlProcessor(String baseUrl, String isbn, Map<String, String> httpHeaders) {
        this.baseUrl = baseUrl;
        this.isbn = isbn;
        this.httpHeaders = httpHeaders;
        this.httpClient = ClientBuilder.newBuilder().build();
    }

    @Override
    public void execute() throws Exception {
        String infoPath = baseUrl + "/api/v2/epubs/urn:orm:book:" + isbn;
        Builder builder = httpClient.target(infoPath).request();
        addHeaders(builder);
        Map<String, Object> response = builder.get(new GenericType<Map<String, Object>>() {
        });
        String title = response.get("title").toString().toLowerCase().replaceAll(" ", "-");
        bookName = safeFileName(title);
        bookFolder = BOOKS_FOLDER + isbn + "-" + bookName;
        File bookDir = new File(bookFolder);
        File book = new File(bookFolder + "/" + bookName + ".html");
        if (!book.exists()) {
            String filesUrl = response.get("files").toString();
            do {
                System.out.println("Searching book pages in " + filesUrl);
                filesUrl = downloadPages(book, filesUrl);
            } while (filesUrl != null);
            download(baseUrl + "/files/public/epub-reader/override_v1.css");
            download(baseUrl + "/library/view/dist/orm.bb9f0f2cd05444f089bc.css");
            download(baseUrl + "/library/view/dist/main.5cf5ecffc5bed2a332c4.css");
            resolveLinks(book);
//            File epub = new File(bookFolder + "/" + bookName + ".epub");
//            createEpub(title, epub, book);
        } else {
            System.out.println("Book " + bookDir.getAbsolutePath() + " already exists, skipping.");
        }
        
    }

    private void createEpub(String titleOriginal, File epub, File html) throws Exception {
        Book book = new Book();
        Metadata metadata = book.getMetadata();
        metadata.addTitle(titleOriginal);
        try (InputStream in = new FileInputStream(html)) {
            book.getResources().add(new Resource(in, html.getName()));
        }
        EpubWriter epubWriter = new EpubWriter();
        try (FileOutputStream out = new FileOutputStream(epub)) {
            epubWriter.write(book, out);
        }
    }

    private void addHeaders(Builder builder) {
        for (Entry<String, String> entry : httpHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
    }

    private void resolveLinks(File book) throws IOException {
        Document document = Jsoup.parse(book, "UTF-8");
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        document.outputSettings().escapeMode(EscapeMode.xhtml);
        Element head = document.getElementsByTag("head").first();
        Element meta = document.createElement("meta");
//              document.getElementsByAttribute("epub:type").remove();
        meta.attr("charset", "utf-8");
        head.appendChild(meta);
        meta = document.createElement("title");
        meta.val(bookName);
        head.appendChild(meta);
        try (Stream<Path> stream = Files.walk(book.getParentFile().toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> !file.toFile().getAbsolutePath().equals(book.getAbsolutePath())).forEach(file -> {
                        File toFile = file.toFile();
                        if (toFile.getName().endsWith(".css")) {
                            Elements styles = document.getElementsByTag("style");
                            Element style = null;
                            if (styles.size() == 0) {
                                style = document.createElement("style");
                                head.appendChild(style);
                            } else {
                                style = styles.first();
                            }
                            try (InputStream input = new FileInputStream(toFile.getAbsolutePath())) {
                                style.append(new String(input.readAllBytes(), Charset.forName("UTF-8")));
                            } catch (IOException e) {
                                throw new IllegalArgumentException("Cannot read " + toFile, e);
                            }
                        }
                    });
        }
        for (Element element : document.getElementsByAttribute("src")) {
            String value = bookFolder + "/" + safeFileName(element.attr("src"));
            try (InputStream input = new FileInputStream(value)) {
                String base64 = Base64.getEncoder().encodeToString(input.readAllBytes());
                element.attr("src", "data:image/png;base64," + base64);
            } catch (Exception e) {
                System.out.println("WARNING: Cannot resolve " + value);
            }
        }
        try (FileOutputStream fos = new FileOutputStream(book);
                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            bos.write(document.outerHtml().getBytes(UTF_8));
        }
    }

    private void downloadPages(File book, List<Map<String, Object>> pages) throws IOException, InterruptedException {
        try (FileOutputStream fos = new FileOutputStream(book, true);
                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
            for (Map<String, Object> page : pages) {
                String pageUrl = (String) page.get("url");
                String mediaType = (String) page.get("media_type");
                System.out.println("Downloading " + mediaType + " " + pageUrl);
                if (VALID_MEDIA_TYPES.contains(mediaType)) {
                    Builder builder = httpClient.target(pageUrl).request();
                    addHeaders(builder);
                    Response content = builder.get();
                    String str = content.readEntity(String.class);
                    if (content.getStatus() == 200) {
                        bos.write(str.getBytes(UTF_8));
                        bos.flush();
                    } else {
                        throw new IllegalArgumentException(
                                "Unexpected HTTP code: " + content.getStatus() + " with content: " + str);
                    }
                } else {
                    download(pageUrl);
                }
            }
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

    private String downloadPages(File book, String filesUrl) throws IOException, InterruptedException {
        if (!book.exists()) {
            book.getParentFile().mkdirs();
            book.createNewFile();
        }
        Builder builder = httpClient.target(filesUrl).request();
        addHeaders(builder);
        Map<String, Object> response = builder.get().readEntity(new GenericType<Map<String, Object>>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) response.get("results");
        downloadPages(book, pages);
        return (String) response.get("next");
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
