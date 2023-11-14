package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Metadata;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubWriter;

public class OreillyProcessor {

    private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
    private static final String BOOKS_FOLDER = "books/";
    private final Client httpClient;
    private final String isbn;
    private final String baseUrl;
    private final Map<String, String> httpHeaders;
    private String bookName;
    private String bookFolder;
    private Book book = new Book();

    public OreillyProcessor(String baseUrl, String isbn, Map<String, String> httpHeaders) {
        this.baseUrl = baseUrl;
        this.isbn = isbn;
        this.httpHeaders = httpHeaders;
        this.httpClient = ClientBuilder.newBuilder().build();
    }

    public void execute() throws Exception {
        String infoPath = baseUrl + "/api/v2/epubs/urn:orm:book:" + isbn;
        Builder builder = httpClient.target(infoPath).request();
        addHeaders(builder);
        Map<String, Object> response = builder.get(new GenericType<Map<String, Object>>() {
        });
        String titleOriginal = response.get("title").toString();
        String title = titleOriginal.toLowerCase().replaceAll(" ", "-");
        bookName = safeFileName(title);
        bookFolder = BOOKS_FOLDER + bookName;
        File bookDir = new File(bookFolder);
        String epub = BOOKS_FOLDER + bookName + ".epub";
        if (!bookDir.exists()) {
            String filesUrl = response.get("files").toString();
            do {
                System.out.println("Searching book pages in " + filesUrl);
                filesUrl = downloadPages(filesUrl);
            } while (filesUrl != null);
        } else {
            System.out.println("Book " + bookDir.getAbsolutePath() + " already exists, skipping.");
        }
        Metadata metadata = book.getMetadata();
        metadata.addTitle(titleOriginal);
        EpubWriter epubWriter = new EpubWriter();
        epubWriter.write(book, new FileOutputStream(epub));     
    }

    private void addHeaders(Builder builder) {
        for (Entry<String, String> entry : httpHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
    }

    private String downloadPages(String filesUrl) throws IOException, InterruptedException {
        Builder builder = httpClient.target(filesUrl).request();
        addHeaders(builder);
        Map<String, Object> response = builder.get().readEntity(new GenericType<Map<String, Object>>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) response.get("results");
        for (Map<String, Object> page : pages) {
            String pageUrl = (String) page.get("url");
            String mediaType = (String) page.get("media_type");
            String fullPath = (String) page.get("full_path");
            String fileName = (String) page.get("filename");
            // stylesheet, chapter, image, other_asset
            String kind = (String) page.get("kind");
            System.out.println("Downloading " + mediaType + " " + pageUrl);
            builder = httpClient.target(pageUrl).request();
            addHeaders(builder);
            Response content = builder.get();
            InputStream input = content.readEntity(InputStream.class);
            byte[] in = input.readAllBytes();
            if ("chapter".equals(kind)) {
                book.addSection(fileName, new Resource(new ByteArrayInputStream(in), fullPath));
            } else {
                book.getResources().add(new Resource(new ByteArrayInputStream(in), fullPath));
            }
            
        }
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
