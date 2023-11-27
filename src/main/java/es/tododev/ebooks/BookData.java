package es.tododev.ebooks;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
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
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

public class BookData {

    private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
    static final Pattern PATTERN = Pattern.compile("src=\"(.*?)\"");
    private final Client httpClient;
    private final String isbn;
    private final String baseUrl;
    private final Map<String, String> httpHeaders;
    private final Set<ResourceItem> resources = new LinkedHashSet<>();
    private final Map<String, ResourceItem> media = new HashMap<>();
    private String titleOriginal;
    private String bookName;
    private ResourceItem coverPage;
    private ResourceItem coverImage;
    private ResourceItem opfResource;
    private ResourceItem ncxResource;
    private ResourceItem cssResource;

    public BookData(Client httpClient, String baseUrl, String isbn, Map<String, String> httpHeaders) {
        this.baseUrl = baseUrl;
        this.isbn = isbn;
        this.httpHeaders = httpHeaders;
        this.httpClient = httpClient;
    }

    public void fetch() throws Exception {
        download();
        postProcessing();
    }

    private void postProcessing() throws UnsupportedEncodingException {
        for (ResourceItem item : resources) {
            if ("chapter".equals(item.kind)) {
                fixMediaLinks(item, false);
            }
        }
        if (coverPage == null) {
            // FIXME Check NCX file
            coverPage = resources.iterator().next();
            resources.remove(coverPage);
        }
        fixMediaLinks(coverPage, true);
    }

    private void fixMediaLinks(ResourceItem item, boolean cover) throws UnsupportedEncodingException {
        if ("chapter".equals(item.kind)) {
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
            item.content = content.getBytes("UTF-8");
        }
    }

    private void download() throws IOException, InterruptedException {
        String infoPath = baseUrl + "/api/v2/epubs/urn:orm:book:" + isbn;
        Builder builder = httpClient.target(infoPath).request();
        addHeaders(builder);
        Map<String, Object> response = builder.get(new GenericType<Map<String, Object>>() {
        });
        titleOriginal = response.get("title").toString();
        String title = titleOriginal.toLowerCase().replaceAll(" ", "-");
        bookName = safeFileName(title);
        String filesUrl = response.get("files").toString();
        do {
            System.out.println("Searching book pages in " + filesUrl);
            filesUrl = downloadPages(filesUrl);
        } while (filesUrl != null);
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
            } else if ("stylesheet".equals(kind)) {
                cssResource = resource;
            } else if (fileName.toLowerCase().endsWith(".opf")) {
                opfResource = resource;
            } else if (fileName.toLowerCase().endsWith(".ncx")) {
                ncxResource = resource;
            } else if (fileName.toLowerCase().contains("cover")) {
                coverPage = resource;
            } else {
                resources.add(resource);
            }
        }
        return (String) response.get("next");
    }

    public Set<ResourceItem> getResources() {
        return resources;
    }

    public Map<String, ResourceItem> getMedia() {
        return media;
    }

    public String getTitleOriginal() {
        return titleOriginal;
    }

    public String getBookName() {
        return bookName;
    }

    public ResourceItem getCoverPage() {
        return coverPage;
    }

    public ResourceItem getCoverImage() {
        return coverImage;
    }

    public ResourceItem getOpfResource() {
        return opfResource;
    }

    public ResourceItem getNcxResource() {
        return ncxResource;
    }

    public ResourceItem getCssResource() {
        return cssResource;
    }


    static class ResourceItem {
        final String pageUrl;
        final String mediaType;
        final String fullPath;
        final String fileName;
        final String kind;
        final String folder;
        byte[] content;

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
