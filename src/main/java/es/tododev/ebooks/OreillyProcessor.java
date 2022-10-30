package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class OreillyProcessor {

	private static final List<String> VALID_MEDIA_TYPES = Arrays.asList("text/plain", "text/html", "text/xml", "text/xhtml", "application/xhtml+xml", "application/xhtml", "application/xml", "application/html");
	private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
	private static final String BOOKS_FOLDER = "books/";
	private final Client httpClient;
	private final String isbn;
	private final String baseUrl;
	private final Map<String, String> httpHeaders;
	private String bookName;

	public OreillyProcessor(String baseUrl, String isbn, Map<String, String> httpHeaders) {
		this.baseUrl = baseUrl;
		this.isbn = isbn;
		this.httpHeaders = httpHeaders;
		this.httpClient = ClientBuilder.newBuilder().build();
	}
	
	public void execute() throws IOException, InterruptedException {
		String infoPath = baseUrl + "/api/v2/epubs/urn:orm:book:" + isbn;
		Builder builder = httpClient.target(infoPath).request();
		addHeaders(builder);
		Map<String, Object> response = builder.get(new GenericType<Map<String, Object>>() {});
		String title = response.get("title").toString().toLowerCase().replaceAll(" ", "-");
		bookName = safeFileName(title);
		File bookDir = new File(BOOKS_FOLDER + bookName);
		if (!bookDir.exists()) {
			String filesUrl = response.get("files").toString();
			do {
				System.out.println("Searching book pages in " + filesUrl);
				filesUrl = downloadPages(filesUrl);
			} while (filesUrl != null);
			downloadSources("img", "src");
			String viewPage = baseUrl + "/library/view/" + title + "/" + isbn;
			downloadStyles(viewPage);
		} else {
			System.out.println("Book " + bookDir.getAbsolutePath() + " already exists, skipping.");
		}
	}
	
	private void addHeaders(Builder builder) {
		for (Entry<String, String> entry : httpHeaders.entrySet()) {
			builder.header(entry.getKey(), entry.getValue());
		}
	}

	private void downloadStyles(String viewPage) throws IOException, InterruptedException {
		File mainView = new File(BOOKS_FOLDER + bookName + "/view.htm");
		if (!mainView.exists()) {
			Builder builder = httpClient.target(viewPage).request();
			addHeaders(builder);
			Response response = builder.get();
			if (response.getStatus() != 200) {
				throw new IllegalArgumentException("Unexpected " + viewPage + " code " + response.getStatus());
			} else {
				mainView.getParentFile().mkdirs();
				InputStream content = response.readEntity(InputStream.class);
				try (FileOutputStream fos = new FileOutputStream(mainView);
		                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
					bos.write(content.readAllBytes());
				}
			}
		}
		downloadSources(mainView, "link", "href");
		Document mainDoc = Jsoup.parse(mainView, "UTF-8");
		Elements styles = mainDoc.getElementsByTag("link");
		File root = new File(BOOKS_FOLDER + bookName);
        for (File html : root.listFiles()) {
        	if (!html.isDirectory() && html.getName().endsWith("html")) {
        		Document doc = Jsoup.parse(html, "UTF-8");
        		Element head = doc.getElementsByTag("head").first();
        		for (Element style : styles) {
        			head.appendChild(style);
        		}
        		try (FileOutputStream fos = new FileOutputStream(html);
		                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
					 bos.write(doc.outerHtml().getBytes(Charset.forName("UTF-8")));
				}
        	}
        }
	}

	private String downloadPages(String filesUrl) throws IOException, InterruptedException {
		File localChapter = new File(BOOKS_FOLDER + bookName + "/" + bookName + ".html");
		if (!localChapter.exists()) {
			localChapter.getParentFile().mkdirs(); 
			localChapter.createNewFile();
		}
		Builder builder = httpClient.target(filesUrl).request();
		addHeaders(builder);
		Map<String, Object> response = builder.get().readEntity(new GenericType<Map<String, Object>>() {});
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> pages = (List<Map<String, Object>>) response.get("results");
		try (FileOutputStream fos = new FileOutputStream(localChapter, true);
                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
			for (Map<String, Object> page : pages) {
				String pageUrl = (String) page.get("url");
				String mediaType = (String) page.get("media_type");
				// Only add valid media types in the book
				if (VALID_MEDIA_TYPES.contains(mediaType)) {
					System.out.println("Downloading " + mediaType + " " + pageUrl);
					builder = httpClient.target(pageUrl).request();
					addHeaders(builder);
					Response content = builder.get();
					String str = content.readEntity(String.class);
					if (content.getStatus() == 200) {
						bos.write(str.getBytes(Charset.forName("UTF-8")));
						bos.flush();
					} else {
						throw new IllegalArgumentException("Unexpected HTTP code: " + content.getStatus() + " with content: " + str);
					}
				} else {
					System.out.println("Skipping invalid " + mediaType + " " + pageUrl);
				}
			}
			
		}
		return (String) response.get("next");
	}

	private void downloadSources(File html, String tag, String attribute) throws IOException, InterruptedException {
		Document doc = Jsoup.parse(html, "UTF-8");
    	Elements elements = doc.getElementsByTag(tag);
    	for (int i = 0; i < elements.size(); i++) {
    		Element tagElement = elements.get(i);
    		String attrValue = tagElement.attr(attribute);
    		// Avoid SO errors with wrong file name characters
    		String safeAttr = safeFileName(attrValue);
    		File dest = new File(BOOKS_FOLDER + bookName + "/" + safeAttr);
    		if (!dest.exists()) {
    			String requestUrl = attrValue.startsWith("http") ? attrValue : baseUrl + attrValue;
    			System.out.println("Downloading " + requestUrl);
    			Builder builder = httpClient.target(requestUrl).request();
    			addHeaders(builder);
    			Response response = builder.get();
    			if (response.getStatus() != 200) {
    				System.out.println("Unexpected " + requestUrl + " code " + response.getStatus());
    			} else {
    				dest.getParentFile().mkdirs();
    				InputStream content = response.readEntity(InputStream.class);
    				try (FileOutputStream fos = new FileOutputStream(dest);
    		                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
    					bos.write(content.readAllBytes());
    				}
    			}
    		} else {
    			System.out.println("Skipping " + dest.getAbsolutePath() + " because it already exists");
    		}
    		if (!safeAttr.equals(attrValue)) {
    			tagElement.attr(attribute, safeAttr);
    			// Update file
    			try (FileOutputStream fos = new FileOutputStream(html);
		                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
					bos.write(doc.outerHtml().getBytes(Charset.forName("UTF-8")));
				}
    		}
    	}
	}

	private void downloadSources(String tag, String attribute) throws IOException, InterruptedException {
		File root = new File(BOOKS_FOLDER + bookName);
        for (File html : root.listFiles()) {
        	if (!html.isDirectory() && html.getName().endsWith("html")) {
        		downloadSources(html, tag, attribute);
        	}
        }
	}
	
	private String safeFileName(String original) {
		if (original.startsWith("https://")) {
			original = original.replaceAll("https://", "");
		}
		if (original.charAt(0) == '/') {
			original = original.substring(1);
		}
		for (String banned : BANNED_CHARACTERS_FILE) {
			original = original.replaceAll(banned, "");
		}
		return original;
	}
}
