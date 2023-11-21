package es.tododev.ebooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import es.tododev.ebooks.BookData.ResourceItem;

public class RelativePathsTest {

    @Test
    public void equalSize() {
        ResourceItem item = new ResourceItem(null, null, "test.d", "test.d", null, null);
        assertEquals("", item.folder);
        item = new ResourceItem(null, null, "test/test.d", "test.d", null, null);
        assertEquals("test/", item.folder);
    }
    
    @Test
    public void relativize1() {
        ResourceItem item1 = new ResourceItem(null, null, "images/477043_2_En_2_Chapter/test.png", "test.png", null, null);
        ResourceItem item2 = new ResourceItem(null, null, "html/test.xhtml", "test.xhtml", null, null);
        assertEquals("../images/477043_2_En_2_Chapter/", ResourceItem.relativize(item2, item1));
    }

    @Test
    public void relativize2() {
        ResourceItem item1 = new ResourceItem(null, null, "assets/test.png", "test.png", null, null);
        ResourceItem item2 = new ResourceItem(null, null, "test.xhtml", "test.xhtml", null, null);
        assertEquals("assets/", ResourceItem.relativize(item2, item1));
    }
}
