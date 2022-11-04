package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.adobe.epubcheck.api.EpubCheck;

public class EpubMaker {

    private static final List<String> IMGS = Arrays.asList("jpg", "png", "gif", "jpeg", "bmp");
    private static final List<String> STYLES = Arrays.asList("css");
    private static final List<String> HTML = Arrays.asList("html", "xhtml");
    private static final List<String> OPF = Arrays.asList("opf");
    private static final List<String> OTF = Arrays.asList("otf");
    private static final Map<String, String> MEDIA_TYPES = new HashMap<>();
    private static final String NCX_TEMPLATE;
    private static final String OPF_TEMPLATE;
    private static final String CONTAINER_TEMPLATE;
    private static final String UUID_REPLACER = "#UUID#";
    private static final String BOOK_NAME_REPLACER = "#BOOK_NAME#";
    private static final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    private static final TransformerFactory transformerFactory = TransformerFactory.newInstance();
    private final String uuid = UUID.randomUUID().toString();
    private final String bookName;
    private final String bookFolder;

    static {
        NCX_TEMPLATE = loadResource("/epub/template.ncx");
        OPF_TEMPLATE = loadResource("/epub/template.opf");
        CONTAINER_TEMPLATE = loadResource("/epub/META-INF/container.xml");
        for (String img : IMGS) {
            MEDIA_TYPES.put(img, "image/" + img);
        }
        for (String style : STYLES) {
            MEDIA_TYPES.put(style, "text/css");
        }
        for (String html : HTML) {
            MEDIA_TYPES.put(html, "application/xhtml+xml");
        }
        for (String opf : OPF) {
            MEDIA_TYPES.put(opf, "application/oebps-package+xml");
        }
        for (String otf : OTF) {
            MEDIA_TYPES.put(otf, "application/vnd.ms-opentype");
        }
    }

    public EpubMaker(String bookFolder, String bookName) {
        this.bookFolder = bookFolder;
        this.bookName = bookName;
    }

    public void execute() throws Exception {
        String epubFile = OreillyProcessor.BOOKS_FOLDER + bookName + ".epub";
        File epub = new File(epubFile);
        if (!epub.exists()) {
            String ncx = fixTemplate(NCX_TEMPLATE);
            String opf = fixTemplate(OPF_TEMPLATE);
            String container = fixTemplate(CONTAINER_TEMPLATE);

            Document opfDoc = buildOpf(opf);
            saveDocument(opfDoc, new File(bookFolder + "/" + bookName + ".opf"));
            writeInPath(container, new File(bookFolder + "/META-INF/container.xml"));
            writeInPath(ncx, new File(bookFolder + "/" + bookName + ".ncx"));
            zipFolder(bookFolder, epubFile);
            validateEpub(epubFile);
        }
    }
    
    private void validateEpub(String epub) {
        EpubCheck epubcheck = new EpubCheck(new File(epub));
        if (!epubcheck.validate()) {
            throw new IllegalStateException("EPUB validation failed in " + epub);
        }
    }

    private void saveDocument(Document document, File dest) throws IOException, TransformerException {
        if (!dest.exists()) {
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            DOMSource source = new DOMSource(document);
            try (FileWriter writer = new FileWriter(dest)) {
                StreamResult result = new StreamResult(writer);
                transformer.transform(source, result);
            }
        }
    }

    private Document buildOpf(String opf) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilder dBuilder = factory.newDocumentBuilder();
        Document document = dBuilder.parse(new ByteArrayInputStream(opf.getBytes(Charset.forName("UTF-8"))));
        Element manifest = (Element) document.getElementsByTagName("manifest").item(0);
        Files.walk(Paths.get(bookFolder)).filter(Files::isRegularFile).forEach(path -> {
            String fileName = path.getFileName().toString();
            String extension = fileName.split("\\.")[1];
            String mediaType = MEDIA_TYPES.get(extension);
            if (mediaType != null) {
                Element item = document.createElement("item");
                item.setAttribute("id", fileName);
                item.setAttribute("href", path.toString().replaceFirst(OreillyProcessor.BOOKS_FOLDER + bookName + "/", ""));
                item.setAttribute("media-type", mediaType);
                manifest.appendChild(item);
            }
        });
        return document;
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

    private String fixTemplate(String template) throws SAXException, IOException {
        String replaced = template.replaceAll(UUID_REPLACER, uuid).replaceAll(BOOK_NAME_REPLACER, bookName);
        return replaced;
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

    private static String loadResource(String resource) {
        try (InputStream input = EpubMaker.class.getResourceAsStream(resource)) {
            return new String(input.readAllBytes(), Charset.forName("UTF-8"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load resource " + resource, e);
        }
    }
}
