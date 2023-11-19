package es.tododev.ebooks;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Metadata;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubWriter;

public class NoDRMProcessor implements Processor {

    private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
    private static final String BOOKS_FOLDER = "books/";
    static final Pattern PATTERN = Pattern.compile("src=\"(.*?)\"");
    private final Client httpClient;
    private final String isbn;
    private final String baseUrl;
    private final Map<String, String> httpHeaders;
    private final Set<ResourceItem> resources = new LinkedHashSet<>();
    private final Map<String, ResourceItem> media = new HashMap<>();
    private ResourceItem opfResource;
    private ResourceItem ncxResource;
    private String bookName;

    public NoDRMProcessor(String baseUrl, String isbn, Map<String, String> httpHeaders) {
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
        String titleOriginal = response.get("title").toString();
        String title = titleOriginal.toLowerCase().replaceAll(" ", "-");
        bookName = safeFileName(title);
        File epub = new File(BOOKS_FOLDER + isbn + "-" + bookName + ".epub");
        if (!epub.exists()) {
            String filesUrl = response.get("files").toString();
            do {
                System.out.println("Searching book pages in " + filesUrl);
                filesUrl = downloadPages(filesUrl);
            } while (filesUrl != null);
            System.out.println("Generate " + epub.getAbsolutePath());
            createEpub(titleOriginal, epub);
        } else {
            System.out.println("Book " + epub.getAbsolutePath() + " already exists, skipping.");
        }
    }

    private void createEpub(String titleOriginal, File epub) throws Exception {
        Book book = new Book();
        Metadata metadata = book.getMetadata();
        metadata.addTitle(titleOriginal);
        ResourceItem coverImage = null;
        for (ResourceItem item : resources) {
            if ("chapter".equals(item.kind)) {
                boolean cover = item.fileName.toLowerCase().contains("cover");
                String content = new String(item.content, "UTF-8");
                Map<String, String> replacements = new HashMap<>();
                Matcher matcher = PATTERN.matcher(content);
                while (matcher.find()) {
                    String fileNameWithPaths = matcher.group(1);
                    String[] file = fileNameWithPaths.split("/");
                    String fileName = file[file.length - 1];
                    ResourceItem image = media.get(fileName);
                    if (image != null) {
                        String relative = ResourceItem.relativize(item, image);
                        String newImage = relative + image.fileName;
                        replacements.put(fileNameWithPaths, newImage);
                        if (cover && coverImage == null) {
                            System.out.println("Cover image found: " + newImage);
                            coverImage = image;
                        }
                    } else {
                        System.out.println("Warning: No image found for " + fileName + " in page " + item.fullPath);
                    }
                }
                for (Entry<String, String> entry : replacements.entrySet()) {
                    content = content.replaceAll(entry.getKey(), entry.getValue());
                }
                Resource page = new Resource(new ByteArrayInputStream(content.getBytes("UTF-8")), item.fullPath);
                if (cover) {
                    book.setCoverPage(page);
                } else {
                    book.addSection(item.fileName, page);
                }
            } else {
                book.getResources().add(new Resource(new ByteArrayInputStream(item.content), item.fullPath));
            }
        }
        for (ResourceItem item : media.values()) {
            if (item == coverImage) {
                System.out.println("Setting cover image " + item.fullPath);
                book.setCoverImage(new Resource(new ByteArrayInputStream(item.content), item.fullPath));
            } else {
                book.getResources().add(new Resource(new ByteArrayInputStream(item.content), item.fullPath));
            }
        }
        if (opfResource != null) {
            book.setOpfResource(new Resource(new ByteArrayInputStream(opfResource.content), opfResource.fullPath));
        }
        if (ncxResource != null) {
            book.setNcxResource(new Resource(new ByteArrayInputStream(ncxResource.content), ncxResource.fullPath));
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
            ResourceItem resource = new ResourceItem(pageUrl, mediaType, fullPath, fileName, kind, in);
            if ("image".equals(kind) || "video".equals(kind)) {
                media.put(fileName, resource);
            } else if (fileName.toLowerCase().endsWith(".opf")) {
                opfResource = resource;
            } else if (fileName.toLowerCase().endsWith(".ncx")) {
                ncxResource = resource;
            } else {
                resources.add(resource);
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

    static class ResourceItem {
        final String pageUrl;
        final String mediaType;
        final String fullPath;
        final String fileName;
        final String kind;
        final String folder;
        final byte[] content;

        public ResourceItem(String pageUrl, String mediaType, String fullPath, String fileName, String kind,
                byte[] content) {
            this.pageUrl = pageUrl;
            this.mediaType = mediaType;
            this.fullPath = fullPath;
            this.fileName = fileName;
            this.kind = kind;
            this.content = content;
            this.folder = fullPath.substring(0, fullPath.length() - fileName.length());
        }

        @Override
        public String toString() {
            return "ResourceItem [mediaType=" + mediaType + ", fileName=" + fileName + ", kind=" + kind + "]";
        }

        public static String relativize(ResourceItem r1, ResourceItem r2) {
            Path p1 = Paths.get(r1.folder);
            Path p2 = Paths.get(r2.folder);
            return p1.relativize(p2).toString().replaceAll("\\\\", "/") + "/";
        }
    }
}
