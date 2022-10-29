package es.tododev.ebooks;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.xml.parsers.ParserConfigurationException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xml.sax.SAXException;

public class OreillyProcessor {

	private static final List<String> BANNED_CHARACTERS_FILE = Arrays.asList("\\:", "\\*", "\\?", "<", ">", "\\|");
	private static final String BASE_URL_KEY = "base.url";
	private static final String INTRO_VIEW_PAGE_KEY = "intro.view.page";
	private static final String FETCH_URL_KEY = "fetch.url";
	private final Properties properties;
	private final HttpClient httpClient;
	private final String bookFolder;

	public OreillyProcessor(Properties properties) {
		this.bookFolder = properties.getProperty(Main.BOOK_NAME_KEY);
		this.properties = properties;
		this.httpClient = HttpClient.newBuilder().build();
	}
	
	public void execute() throws ParserConfigurationException, SAXException, IOException, InterruptedException {
		downloadPages();
		downloadSources("img", "src");
		downloadStyles();
	}
	
	private void addHeaders(Builder builder) {
		for (Object keyObj : properties.keySet()) {
			String key = (String) keyObj;
			if (key.startsWith("header.")) {
				builder.header(key.replaceFirst("header.", ""), properties.getProperty(key).toString());
			}
		}
	}

	private void downloadStyles() throws IOException, InterruptedException {
		Builder builder = HttpRequest.newBuilder().GET();
		addHeaders(builder);
		String url = properties.getProperty(BASE_URL_KEY) + properties.getProperty(INTRO_VIEW_PAGE_KEY);
		HttpRequest request = builder.uri(URI.create(url)).build();
		File mainView = new File(bookFolder + "/main.htm");
		if (!mainView.exists()) {
			mainView.getParentFile().mkdirs();
			HttpResponse<Path> response = httpClient.send(request, BodyHandlers.ofFile(Path.of(mainView.toURI())));
			if (response.statusCode() != 200) {
				throw new IllegalArgumentException("Unexpected HTTP code: " + response.statusCode() + " with content: " + response.body());
			}
		}
		downloadSources(mainView, "link", "href");
		Document mainDoc = Jsoup.parse(mainView, "UTF-8");
		Elements styles = mainDoc.getElementsByTag("link");
		File root = new File(bookFolder);
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

	private void downloadPages() throws IOException, InterruptedException {
		Builder builder = HttpRequest.newBuilder().GET();
		addHeaders(builder);
		String baseUrl = properties.getProperty(BASE_URL_KEY) + properties.getProperty(FETCH_URL_KEY);
		boolean finish = false;
		int i = 1;
		File localChapter = new File(bookFolder + "/" + bookFolder + ".html");
		if (!localChapter.exists()) {
			localChapter.getParentFile().mkdirs(); 
			localChapter.createNewFile();
			try (FileOutputStream fos = new FileOutputStream(localChapter);
	                BufferedOutputStream bos = new BufferedOutputStream(fos)) {
				while (!finish) {
					String number = i < 10 ? "0" + i : Integer.toString(i);
					String fileName = "chapter-" + number + ".html";
					String url = baseUrl.replaceFirst("#CHAPTER#", fileName);
					HttpRequest request = builder.uri(URI.create(url)).build();
					HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
					if (response.statusCode() == 200) {
						bos.write(response.body().getBytes(Charset.forName("UTF-8")));
						bos.flush();
					} else if (response.statusCode() == 404) {
						finish = true;
					} else {
						throw new IllegalArgumentException("Unexpected HTTP code: " + response.statusCode() + " with content: " + response.body());
					}
					i++;
				}
				
			}
		} else {
			System.out.println("Skipping " + localChapter.getAbsolutePath() + " because it already exists");
		}
	}

	private void downloadSources(File html, String tag, String attribute) throws IOException, InterruptedException {
		Builder builder = HttpRequest.newBuilder().GET();
		String baseUrl = properties.getProperty(BASE_URL_KEY);
		addHeaders(builder);
		Document doc = Jsoup.parse(html, "UTF-8");
    	Elements elements = doc.getElementsByTag(tag);
    	for (int i = 0; i < elements.size(); i++) {
    		Element tagElement = elements.get(i);
    		String attrValue = tagElement.attr(attribute);
    		// Avoid SO errors with wrong file name characters
    		String safeAttr = safeFileName(attrValue);
    		File dest = new File(bookFolder + "/" + safeAttr);
    		if (!dest.exists()) {
    			String requestUrl = attrValue.startsWith("http") ? attrValue : baseUrl + attrValue;
    			HttpRequest request = builder.uri(URI.create(requestUrl)).build();
    			Path download = Path.of(dest.toURI());
    			dest.getParentFile().mkdirs();
    			HttpResponse<Path> response = httpClient.send(request, BodyHandlers.ofFile(download));
    			if (response.statusCode() != 200) {
    				System.out.println("Unexpected " + requestUrl + " code " + response.statusCode() + " with: " + response.body());
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
		File root = new File(bookFolder);
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
