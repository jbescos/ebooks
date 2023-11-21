package es.tododev.ebooks;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;

import es.tododev.ebooks.BookData.ResourceItem;
import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Metadata;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubWriter;

public class EpubProcessor implements Processor {

    private static final String BOOKS_FOLDER = "books/";
    private final Client httpClient;
    private final String isbn;
    private final String baseUrl;
    private final Map<String, String> httpHeaders;

    public EpubProcessor(String baseUrl, String isbn, Map<String, String> httpHeaders) {
        this.baseUrl = baseUrl;
        this.isbn = isbn;
        this.httpHeaders = httpHeaders;
        this.httpClient = ClientBuilder.newBuilder().build();
    }

    @Override
    public void execute() throws Exception {
        BookData book = new BookData(httpClient, baseUrl, isbn, httpHeaders);
        book.fetch();
        File epub = new File(BOOKS_FOLDER + isbn + "-" + book.getBookName() + ".epub");
        createEpub(book, epub);
        System.out.println("Generated " + epub.getAbsolutePath());
    }

    private void createEpub(BookData data, File epub) throws Exception {
        Book book = new Book();
        Metadata metadata = book.getMetadata();
        metadata.addTitle(data.getTitleOriginal());
        for (ResourceItem item : data.getResources()) {
            if ("chapter".equals(item.kind)) {
                Resource page = new Resource(new ByteArrayInputStream(item.content), item.fullPath);
                book.addSection(item.fileName, page);
            } else {
                book.getResources().add(new Resource(new ByteArrayInputStream(item.content), item.fullPath));
            }
        }
        for (ResourceItem item : data.getMedia().values()) {
            if (item == data.getCoverImage()) {
                book.setCoverImage(new Resource(new ByteArrayInputStream(item.content), item.fullPath));
            } else {
                book.getResources().add(new Resource(new ByteArrayInputStream(item.content), item.fullPath));
            }
        }
        ResourceItem cssResource = data.getCssResource();
        if (cssResource != null) {
            book.getResources().add(new Resource(new ByteArrayInputStream(cssResource.content), cssResource.fullPath));
        }
        ResourceItem coverPage = data.getCoverPage();
        if (coverPage != null) {
            book.setCoverPage(new Resource(new ByteArrayInputStream(coverPage.content), coverPage.fullPath));
        }
        ResourceItem opfResource = data.getOpfResource();
        if (opfResource != null) {
            book.setOpfResource(new Resource(new ByteArrayInputStream(opfResource.content), opfResource.fullPath));
        }
        ResourceItem ncxResource = data.getNcxResource();
        if (ncxResource != null) {
            book.setNcxResource(new Resource(new ByteArrayInputStream(ncxResource.content), ncxResource.fullPath));
        }
        EpubWriter epubWriter = new EpubWriter();
        try (FileOutputStream out = new FileOutputStream(epub)) {
            epubWriter.write(book, out);
        }
    }

}
