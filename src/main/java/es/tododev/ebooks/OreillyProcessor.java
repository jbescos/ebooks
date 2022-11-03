package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

public class OreillyProcessor {

	private static final List<String> VALID_MEDIA_TYPES = Arrays.asList("text/plain", "text/html", "text/xml", "text/xhtml", "application/xhtml+xml", "application/xhtml", "application/xml", "application/html");
	private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
	private static final String BOOKS_FOLDER = "books/";
	private final Client httpClient;
	private final String isbn;
	private final String baseUrl;
	private final Map<String, String> httpHeaders;
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
		Map<String, Object> response = builder.get(new GenericType<Map<String, Object>>() {});
		String title = response.get("title").toString().toLowerCase().replaceAll(" ", "-");
		bookName = safeFileName(title);
		bookFolder = BOOKS_FOLDER + bookName;
		File book = new File(bookFolder + "/" + bookName + ".html");
		if (!book.exists()) {
			String filesUrl = response.get("files").toString();
			do {
				System.out.println("Searching book pages in " + filesUrl);
				filesUrl = downloadPages(book, filesUrl);
			} while (filesUrl != null);
			resolveLinks(book);
		} else {
			System.out.println("Book " + book.getAbsolutePath() + " already exists, skipping.");
		}
		createEpub(book);
	}
	
	private void createEpub(File book) throws Exception {
	    new EpubMaker(bookFolder, bookName).execute();
	}
	
	private void addHeaders(Builder builder) {
		for (Entry<String, String> entry : httpHeaders.entrySet()) {
			builder.header(entry.getKey(), entry.getValue());
		}
	}
	
	private void resolveLinks(File book) throws IOException {
		Document document = Jsoup.parse(book, "UTF-8");
		Element head = document.getElementsByTag("head").first();
		Element meta = document.createElement("meta");
		meta.attr("charset", "utf-8");
		head.appendChild(meta);
		Files.walk(book.getParentFile().toPath()).filter(Files::isRegularFile).filter(file -> !file.toFile().getAbsolutePath().equals(book.getAbsolutePath()))
	          .forEach(file -> {
	        	  File toFile = file.toFile();
	        	  if (toFile.getName().endsWith(".css")) {
	        		  Element link = document.createElement("link");
	        		  link.attr("rel", "stylesheet");
	        		  int idx = book.getParentFile().getAbsolutePath().length();
	        		  link.attr("href", safeFileName(toFile.getAbsolutePath().substring(idx)));
	        		  head.appendChild(link);
	        	  }
	          });
		for (Element element : document.getElementsByAttribute("src")) {
			String value = safeFileName(element.attr("src"));
			element.attr("src", value);
		}
		try (FileOutputStream fos = new FileOutputStream(book);
                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
			 bos.write(document.outerHtml().getBytes(Charset.forName("UTF-8")));
		}
	}

	private String downloadPages(File book, String filesUrl) throws IOException, InterruptedException {
		if (!book.exists()) {
		    book.getParentFile().mkdirs(); 
		    book.createNewFile();
		}
		Builder builder = httpClient.target(filesUrl).request();
		addHeaders(builder);
		Map<String, Object> response = builder.get().readEntity(new GenericType<Map<String, Object>>() {});
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> pages = (List<Map<String, Object>>) response.get("results");
		try (FileOutputStream fos = new FileOutputStream(book, true);
                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
			for (Map<String, Object> page : pages) {
				String pageUrl = (String) page.get("url");
				String mediaType = (String) page.get("media_type");
				System.out.println("Downloading " + mediaType + " " + pageUrl);
				if (VALID_MEDIA_TYPES.contains(mediaType)) {
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
					String filePath = safeFileName(bookFolder + pageUrl.replaceFirst(baseUrl, ""));
					File resource = new File(filePath);
					if (!resource.exists()) {
						resource.getParentFile().mkdirs();
						resource.createNewFile();
						builder = httpClient.target(pageUrl).request();
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
