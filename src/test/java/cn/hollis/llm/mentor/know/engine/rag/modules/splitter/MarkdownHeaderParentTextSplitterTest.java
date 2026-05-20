package cn.hollis.llm.mentor.know.engine.rag.modules.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.Test;

import java.io.InputStream;
import java.util.List;

public class MarkdownHeaderParentTextSplitterTest {

    @Test
    public void testSplitText() {

        MarkdownHeaderParentTextSplitter markdownHeaderParentTextSplitter = new MarkdownHeaderParentTextSplitter(1000, 80);

        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("MinerU_markdown_r7-product-manual-20250123_2028781865782407168.md");
        DocumentParser parser = new TextDocumentParser();
        Document parsedDocument = parser.parse(inputStream);
        List<TextSegment> segments = markdownHeaderParentTextSplitter.split(parsedDocument);

        System.out.println(segments.size());

        for (TextSegment segment : segments) {
            System.out.println(segment.text());
            System.out.println(segment.metadata());
            System.out.println("======");
        }

    }
}
