package org.eclipse.jetty.wiki;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class FixHtml
{
    private final DocumentBuilderFactory domBuilderFactory;
    private final DocumentBuilder domBuilder;

    private final Path rootDir;
    private final XPath xPath;
    private final TransformerFactory transformerFactory;
    private final Transformer transformer;

    public FixHtml(Path cleanRoot) throws FileNotFoundException, ParserConfigurationException, TransformerConfigurationException
    {
        if (!Files.exists(cleanRoot))
        {
            throw new FileNotFoundException("Unable to find /clean/");
        }

        this.rootDir = cleanRoot;

        domBuilderFactory = DocumentBuilderFactory.newInstance();
        domBuilderFactory.setValidating(false);
        domBuilderFactory.setNamespaceAware(true);
        domBuilderFactory.setFeature("http://xml.org/sax/features/namespaces", false);
        domBuilderFactory.setFeature("http://xml.org/sax/features/validation", false);
        domBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        domBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        domBuilder = domBuilderFactory.newDocumentBuilder();
        transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute("indent-number", 2);
        transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.ENCODING, "utf-8");
        transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "-//W3C//DTD XHTML 1.0 Transitional//EN");
        transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd");
        xPath = XPathFactory.newInstance().newXPath();
    }

    public void walk() throws IOException
    {
        PathMatcher htmlMatcher = rootDir.getFileSystem().getPathMatcher("glob:**/*.html");

        Files.walk(rootDir)
            .filter(Files::isRegularFile)
            .filter(htmlMatcher::matches)
            .forEach(this::processHtml);
    }

    private void processHtml(Path htmlFile)
    {
        Path relativeHtml = rootDir.relativize(htmlFile);

        try
        {
            Document dom = loadHtml(htmlFile);
            // cleanup text nodes
            cleanupTextNodes(dom);

            // tag <pre> nodes
            preserveSpace(dom);

            // kill tablist
            removeNodes(relativeHtml, dom, "//ul[@role='tablist']");

            // kill the old messagebox
            removeNodes(relativeHtml, dom, "//div[@class='messagebox']");

            // kill catlinks
            removeNodes(relativeHtml, dom, "//div[@class='catlinks']");

            saveHtml(dom, htmlFile);
        }
        catch (Exception e)
        {
            System.out.printf("[html|ERROR] %s%n", htmlFile.toAbsolutePath());
            e.printStackTrace(System.out);
        }
    }

    private Document loadHtml(Path htmlFile) throws IOException, SAXException
    {
        System.out.printf("[html] loading %s%n", htmlFile);
        try (BufferedReader reader = Files.newBufferedReader(htmlFile, StandardCharsets.UTF_8))
        {
            InputSource inputSource = new InputSource(reader);
            return domBuilder.parse(inputSource);
        }
    }

    private void cleanupTextNodes(Document dom) throws XPathExpressionException
    {
        NodeList nodeList = (NodeList)xPath.compile("//text()").evaluate(dom, XPathConstants.NODESET);
        List<Node> nodes = toList(nodeList);
        for (Node node : nodes)
        {
            if (!hasParentElement(node, "pre"))
            {
                String rawText = node.getTextContent().trim();
                if (rawText.isBlank())
                {
                    node.getParentNode().removeChild(node);
                }
                else
                {
                    // remove
                    char lrm = '\u200E';
                    rawText = rawText.replaceAll("[" + lrm + "]", "");
                    // make space
                    char nbsp = '\u00A0';
                    rawText = rawText.replaceAll("[" + nbsp + "]", " ");
                    node.setTextContent(rawText);
                }
            }
        }
    }

    private void preserveSpace(Document dom) throws XPathExpressionException
    {
        NodeList nodeList = (NodeList)xPath.compile("//pre").evaluate(dom, XPathConstants.NODESET);
        List<Node> nodes = toList(nodeList);
        for (Node node : nodes)
        {
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;
            Element elem = (Element)node;
            // xml:space="preserve"
            elem.setAttributeNS(XMLConstants.XML_NS_URI, "space", "preserve");
        }
    }

    @SuppressWarnings("SameParameterValue")
    private boolean hasParentElement(Node node, String name)
    {
        Node parent = node.getParentNode();
        while (parent != null)
        {
            if ((parent.getNodeType() == Node.ELEMENT_NODE) &&
                (parent.getNodeName().equals(name)))
            {
                return true;
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    private void saveHtml(Document dom, Path htmlFile) throws IOException, TransformerException
    {
        try (BufferedWriter writer = Files.newBufferedWriter(htmlFile, StandardCharsets.UTF_8))
        {
            Result output = new StreamResult(writer);
            Source input = new DOMSource(dom);

            transformer.transform(input, output);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private void removeNodes(Path relativeHtml, Document dom, String xpathToNode) throws XPathExpressionException
    {
        NodeList nodeList = (NodeList)xPath.compile(xpathToNode).evaluate(dom, XPathConstants.NODESET);
        List<Node> nodes = toList(nodeList);
        if (!nodes.isEmpty())
        {
            System.out.printf("[html] removeNodes[%s] : %s%n", xpathToNode, relativeHtml);
            for (Node node : nodes)
            {
                node.getParentNode().removeChild(node);
            }
        }
    }

    private List<Node> toList(NodeList nodeList)
    {
        if (nodeList == null || nodeList.getLength() == 0)
            return Collections.emptyList();
        List<Node> ret = new ArrayList<>();
        for (int i = 0; i < nodeList.getLength(); i++)
        {
            ret.add(nodeList.item(i));
        }
        return ret;
    }

    public static void main(String[] args) throws Exception
    {
        FixHtml fix = new FixHtml(Paths.get("clean"));
        fix.walk();
    }
}
