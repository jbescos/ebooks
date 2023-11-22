package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities.EscapeMode;
import org.jsoup.select.Elements;
import org.xhtmlrenderer.layout.SharedContext;
import org.xhtmlrenderer.pdf.ITextRenderer;

import es.tododev.ebooks.BookData.ResourceItem;

public class AntiDRMProcessor implements Processor {

    private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
    private static final String BOOKS_FOLDER = "books/";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private final Client httpClient;
    private final String isbn;
    private final String baseUrl;
    private final Map<String, String> httpHeaders;
    private String bookFolder;

    public AntiDRMProcessor(String baseUrl, String isbn, Map<String, String> httpHeaders) {
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
        createHtml(html, book);
        System.out.println("Generated " + html.getAbsolutePath());
        File pdf = new File(BOOKS_FOLDER + isbn + "-" + book.getBookName() + ".pdf");
        createPdf(html, pdf);
        System.out.println("Generated " + pdf.getAbsolutePath());
    }

    private void createPdf(File html, File pdf) throws IOException {
        try (OutputStream outputStream = new FileOutputStream(pdf)) {
            ITextRenderer renderer = new ITextRenderer();
            SharedContext sharedContext = renderer.getSharedContext();
            sharedContext.setPrint(true);
            sharedContext.setInteractive(false);
            renderer.setDocument(html);
            renderer.layout();
            renderer.createPDF(outputStream);
        }
    }
    
    private void createHtml(File html, BookData data) throws IOException {
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
        // Remove not HTML attribute
        for (Element element : document.getElementsByAttribute("epub:type")) {
            element.removeAttr("epub:type");
        }
        // Fix href
        for (Element element : document.getElementsByAttribute("href")) {
            String hrefVal = element.attr("href");
            int idx = hrefVal.indexOf("#");
            if (idx != -1) {
                hrefVal = hrefVal.substring(idx);
                element.attr("href", hrefVal);
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
