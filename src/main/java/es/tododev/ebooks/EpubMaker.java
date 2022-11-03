package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

public class EpubMaker {

	private static final List<String> IMGS = Arrays.asList("jpg", "png", "gif", "jpeg", "bmp");
	private static final List<String> STYLES = Arrays.asList("css");
	private static final List<String> HTML = Arrays.asList("html", "xhtml");
	private static final List<String> OPF = Arrays.asList("opf");
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
	}

	public EpubMaker(String bookFolder, String bookName) {
		this.bookFolder = bookFolder;
		this.bookName = bookName;
	}
	
	public void execute() throws Exception {
		File epub = new File(bookFolder + "/" + bookName + ".epub");
		if (!epub.exists()) {
			String ncx = fixTemplate(NCX_TEMPLATE);
			String opf = fixTemplate(OPF_TEMPLATE);
			String container = fixTemplate(CONTAINER_TEMPLATE);
			
			Document opfDoc = buildOpf(opf);
			saveDocument(opfDoc, new File(bookFolder + "/" + bookName + ".opf"));
	        copy(loadFileInClasspath("/epub/mimetype"), bookFolder + "/mimetype");
	        writeInPath(container, new File(bookFolder + "/META-INF/container.xml"));
	        writeInPath(ncx, new File(bookFolder + "/" + bookName + ".ncx"));
		}
	}
	
	private void saveDocument(Document document, File dest) throws IOException, TransformerException {
		if (!dest.exists()) {
			Transformer transformer = transformerFactory.newTransformer();
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
    			item.setAttribute("href", path.toString());
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
	
	private void copy(File in, String out) throws IOException {
		Path dest = Paths.get(out);
		if (!dest.toFile().exists()) {
			Files.copy(in.toPath(), dest);
		}
		
	}
	
	private File loadFileInClasspath(String resource) throws URISyntaxException {
		File file = new File(EpubMaker.class.getResource(resource).toURI());
		return file;
	}
	
	private static String loadResource(String resource) {
		try (InputStream input = EpubMaker.class.getResourceAsStream(resource)) {
			return new String(input.readAllBytes(), Charset.forName("UTF-8"));
		} catch (IOException e) {
			throw new IllegalStateException("Cannot load resource " + resource, e);
		}
	}
}
