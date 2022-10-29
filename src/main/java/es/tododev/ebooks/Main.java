package es.tododev.ebooks;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

public class Main {
	
	static final String BOOK_NAME_KEY = "book.name";

	public static void main(String[] args) throws IOException, InterruptedException, ParserConfigurationException, SAXException {
		if (args.length != 1) {
			System.out.println("Specify the properties file as argument");
		} else {
			Properties properties = new Properties();
			File prop = new File(args[0]);
			if (!prop.exists()) {
				throw new IllegalArgumentException("File " + args[0] + " does not exist");
			}
			try (InputStream input = new FileInputStream(args[0])) {
				properties.load(input);
			}
			properties.put(BOOK_NAME_KEY, prop.getName().split("\\.")[0]);
			OreillyProcessor processor = new OreillyProcessor(properties);
			processor.execute();
		}
	}

}
