package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.adobe.epubcheck.api.EpubCheck;

public class OreillyProcessor {

    private static final TransformerFactory tf = TransformerFactory.newInstance();
    private static final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    private static final String CONTENT_XML = "META-INF/container.xml";
    private static final String CONTENT_XML_TEMPLATE = "<?xml version=\"1.0\" encoding=\"utf-8\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles></rootfiles></container>";
    private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
    private static final String BOOKS_FOLDER = "books/";
    private final Client httpClient;
    private final String isbn;
    private final String baseUrl;
    private final Map<String, String> httpHeaders;
    private final Map<String, String> opfs = new HashMap<>();
    private String bookName;
    private String bookFolder;

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
        String title = response.get("title").toString().toLowerCase().replaceAll(" ", "-");
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
            writeInPath(generateContent(), new File(bookFolder + "/" + CONTENT_XML));
        } else {
            System.out.println("Book " + bookDir.getAbsolutePath() + " already exists, skipping.");
        }
        zipFolder(bookFolder, epub);
        validateEpub(epub);
    }

    private void validateEpub(String epub) {
        EpubCheck epubcheck = new EpubCheck(new File(epub));
        if (!epubcheck.validate()) {
            System.out.println("EPUB validation failed in " + epub);
        } else {
            System.out.println("EPUB validated succesfully. " + epub);
        }
    }

    private String generateContent()
            throws SAXException, IOException, ParserConfigurationException, TransformerException {
        DocumentBuilder dBuilder = factory.newDocumentBuilder();
        Document document = dBuilder
                .parse(new ByteArrayInputStream(CONTENT_XML_TEMPLATE.getBytes(Charset.forName("UTF-8"))));
        Element rootfiles = (Element) document.getElementsByTagName("rootfiles").item(0);
        for (Entry<String, String> opf : opfs.entrySet()) {
            Element rootfile = document.createElement("rootfile");
            rootfile.setAttribute("full-path", opf.getKey());
            rootfile.setAttribute("media-type", opf.getValue());
            rootfiles.appendChild(rootfile);
        }
        DOMSource domSource = new DOMSource(document);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        Transformer transformer = tf.newTransformer();
        transformer.transform(domSource, result);
        return writer.toString();
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
            if (fullPath.endsWith(".opf")) {
                opfs.put(fullPath, mediaType);
            }
            System.out.println("Downloading " + mediaType + " " + pageUrl);
            File resource = new File(bookFolder + "/" + fullPath);
            if (!resource.exists()) {
                resource.getParentFile().mkdirs();
                resource.createNewFile();
                builder = httpClient.target(pageUrl).request();
                addHeaders(builder);
                Response content = builder.get();
                InputStream input = content.readEntity(InputStream.class);
                try (FileOutputStream fos = new FileOutputStream(resource);
                        BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                    bos.write(input.readAllBytes());
                    bos.flush();
                }
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

    private void zipFolder(String sourceDirPath, String zipFilePath) throws IOException {
        File file = new File(zipFilePath);
        if (!file.exists()) {
            Path p = Files.createFile(Paths.get(zipFilePath));
            try (ZipOutputStream zs = new ZipOutputStream(Files.newOutputStream(p))) {
                addMimetype(zs);
                Path pp = Paths.get(sourceDirPath);
                Files.walk(pp).filter(path -> !Files.isDirectory(path)).forEach(path -> {
                    String zipEntryName = pp.relativize(path).toString().replaceAll("\\\\", "/");
                    ZipEntry zipEntry = new ZipEntry(zipEntryName);
                    try {
                        zs.putNextEntry(zipEntry);
                        Files.copy(path, zs);
                        zs.closeEntry();
                    } catch (IOException e) {
                        throw new IllegalArgumentException("Cannot include " + zipEntry, e);
                    }
                });
            }
        }
    }

    private void addMimetype(ZipOutputStream zs) throws UnsupportedEncodingException, IOException {
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(20);
        entry.setCompressedSize(20);
        entry.setCrc(0x2CAB616F);
        zs.putNextEntry(entry);
        zs.write("application/epub+zip".getBytes("UTF-8"));
    }

    private void writeInPath(String content, File dest) throws IOException {
        if (!dest.exists()) {
            dest.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(dest);
                    BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                bos.write(content.getBytes(Charset.forName("UTF-8")));
                bos.flush();
            }
        }
    }
}
