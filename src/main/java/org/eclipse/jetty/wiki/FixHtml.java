package org.eclipse.jetty.wiki;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    private final Map<String, String> urlRewrites;
    private final List<String> images;
    private Node headingElement;

    public FixHtml(Path cleanRoot) throws IOException, ParserConfigurationException, TransformerConfigurationException
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

        urlRewrites = new HashMap<>();
        urlRewrites.put("http://dist.codehaus.org/jetty", "https://search.maven.org/search?q=g:org.mortbay.jetty%20AND%20v:7.6.16.v20140903");
        urlRewrites.put("http://download.eclipse.org/jetty/stable-7/xref/(.*)", "https://archive.eclipse.org/jetty/7.6.16.v20140903/xref/$1");
        urlRewrites.put("http://download.eclipse.org/jetty/stable-7/apidocs/(.*)", "https://archive.eclipse.org/jetty/7.6.16.v20140903/apidocs/$1");
        urlRewrites.put("http://download.eclipse.org/jetty/stable-8/xref/(.*)", "https://archive.eclipse.org/jetty/8.1.16.v20140903/xref/$1");
        urlRewrites.put("http://download.eclipse.org/jetty/stable-8/apidocs/(.*)", "https://archive.eclipse.org/jetty/8.1.16.v20140903/apidocs/$1");

        images = Files.walk(rootDir.resolve("images"))
            .filter(Files::isRegularFile)
            .map(Path::getFileName)
            .map(Objects::toString)
            .collect(Collectors.toList());
    }

    public void walk() throws IOException
    {
        PathMatcher htmlMatcher = rootDir.getFileSystem().getPathMatcher("glob:**/*.html");

        Files.walk(rootDir)
            .filter(Files::isRegularFile)
            .filter(htmlMatcher::matches)
            .forEach(this::processHtml);

        System.out.printf("[walk] done %s.%n", rootDir);
    }

    private void processHtml(Path htmlFile)
    {
        Path relativeHtml = rootDir.relativize(htmlFile);

        try
        {
            Document dom = loadHtml(htmlFile);

            // copy heading
            fixHeading(htmlFile, dom);

            // fix title
            fixTitle(htmlFile, dom);

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

            // fix stylesheets
            fixStyleSheet(htmlFile, dom);

            // fix href encoding
            fixLinks(htmlFile, dom);

            // fix images
            fixImages(htmlFile, dom);

            saveHtml(dom, htmlFile);
        }
        catch (Exception e)
        {
            System.out.printf("[html|ERROR] %s%n", htmlFile.toAbsolutePath());
            e.printStackTrace(System.out);
        }
    }

    private void fixTitle(Path htmlFile, Document dom) throws XPathExpressionException, IOException, SAXException
    {
        Path baseHtml = rootDir.resolve("Jetty.html");
        if (Files.isSameFile(htmlFile, baseHtml))
            return; // skip

        Path rawDir = rootDir.toAbsolutePath().getParent().resolve("raw");
        Path oldHtml = rawDir.resolve(rootDir.relativize(htmlFile));
        String oldTitle = getHtmlTitle(oldHtml);

        oldTitle = oldTitle.replace(" - Eclipsepedia", " - (Archive Wiki)");

        Element titleElem = xpathFirstElement(dom, "//html/head/title");
        if (titleElem == null)
            throw new IllegalStateException("No html/head/title found");
        titleElem.setTextContent(oldTitle);
    }

    private String getHtmlTitle(Path htmlPath) throws IOException, SAXException, XPathExpressionException
    {
        Document htmlDoc = loadHtml(htmlPath);
        String title = (String)xPath.compile("//html/head/title").evaluate(htmlDoc, XPathConstants.STRING);
        return title;
    }

    private void fixHeading(Path htmlFile, Document dom) throws IOException, SAXException, XPathExpressionException
    {
        Path baseHtml = rootDir.resolve("Jetty.html");
        if (Files.isSameFile(htmlFile, baseHtml))
            return; // skip

        if (headingElement == null)
        {
            Document baseDoc = loadHtml(baseHtml);
            Element elem = xpathFirstElement(baseDoc, "//div[@class='heading']");
            if (elem == null)
                throw new IllegalStateException("Unable to find 'heading' element");
            headingElement = elem.cloneNode(true);
        }

        Element existingHeading = xpathFirstElement(dom, "//div[@class='heading']");
        if (existingHeading != null)
        {
            Element parent = (Element)existingHeading.getParentNode();
            dom.adoptNode(headingElement);
            parent.replaceChild(headingElement, existingHeading);
        }
        else
        {
            // we need to insert
            Element mainContent = xpathStream(dom, "//div[@id='mainContent']")
                .filter(FixHtml::isElement)
                .map(FixHtml::toElement)
                .findFirst()
                .orElseThrow(() ->
                    new IllegalStateException("Unable to find 'mainContent' element"));

            Element parent = (Element)mainContent.getParentNode();
            dom.adoptNode(headingElement);
            parent.insertBefore(headingElement, mainContent);
        }
    }

    private void fixImages(Path htmlFile, Document dom) throws XPathExpressionException
    {
        // <a class="image" href="https://wiki.eclipse.org/File:Jetty-wtp-create1.jpg">
        //   <img alt="Jetty-wtp-create1.jpg" height="500" src="../images/d/d7/Jetty-wtp-create1.jpg" width="500" />
        // </a>

        stripImageLinks(dom);
        reduceImageSrc(htmlFile, dom);
    }

    private void reduceImageSrc(Path htmlFile, Document dom) throws XPathExpressionException
    {
        for (Node node : xpathNodes(dom, "//img"))
        {
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;
            Element elem = (Element)node;
            if (!elem.hasAttribute("src"))
                continue;
            String src = elem.getAttribute("src");
            int lastSlash = src.lastIndexOf('/');
            String filename = src.substring(lastSlash + 1);
            if (images.contains(filename))
            {
                Path imagePath = rootDir.resolve("images/" + filename);
                elem.setAttribute("src", htmlFile.getParent().relativize(imagePath).toString());
            }
            else
            {
                System.out.printf("[img] MISSING? %s - %s%n", htmlFile, src);
            }
        }
    }

    private void stripImageLinks(Document dom) throws XPathExpressionException
    {
        for (Node node : xpathNodes(dom, "//a[@class='image']"))
        {
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;
            Element elem = (Element)node;
            if (!elem.hasAttribute("href"))
                continue;
            String href = elem.getAttribute("href");
            if (href.startsWith("https://wiki.eclipse.org/"))
            {
                elem.removeAttribute("href");
            }
        }
    }

    private void fixStyleSheet(Path htmlFile, Document dom) throws XPathExpressionException
    {
        Path styleSheet = rootDir.resolve("wiki-style.css");
        List<Node> nodes = xpathNodes(dom, "//link[@rel='stylesheet']");
        for (Node node : nodes)
        {
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;
            Element elem = (Element)node;
            String href = htmlFile.getParent().relativize(styleSheet).toString();
            elem.setAttribute("href", href);
        }
    }

    private void fixLinks(Path htmlFile, Document dom) throws XPathExpressionException
    {
        for (Node node : xpathNodes(dom, "//a"))
        {
            if (node.getNodeType() != Node.ELEMENT_NODE)
                continue;
            Element elem = (Element)node;
            if (elem.hasAttribute("href"))
            {
                String rawHref = elem.getAttribute("href");
                if (rawHref.contains(" "))
                {
                    rawHref = rawHref.replaceAll(" ", "%20");
                    elem.setAttribute("href", rawHref);
                }

                fixWikiLink(htmlFile, elem, rawHref, "https://wiki.eclipse.org/");
                fixWikiLink(htmlFile, elem, rawHref, "http://wiki.eclipse.org/");

                for (Map.Entry<String, String> entry : urlRewrites.entrySet())
                {
                    rawHref = rawHref.replaceAll(entry.getKey(), entry.getValue());
                }
                elem.setAttribute("href", rawHref);
            }
        }
    }

    private void fixWikiLink(Path htmlFile, Element elem, String rawHref, String uriPrefix)
    {
        if (rawHref.startsWith(uriPrefix))
        {
            String relativeRef = URLDecoder.decode(rawHref.substring(uriPrefix.length()), StandardCharsets.UTF_8);
            int hashIdx = relativeRef.indexOf('#');
            if (hashIdx > 0)
                relativeRef = relativeRef.substring(0, hashIdx);

            Path hrefPath = getHtmlFile(relativeRef);
            if (Files.exists(hrefPath))
            {
                String href = htmlFile.getParent().toUri().relativize(hrefPath.toUri()).toASCIIString();
                elem.setAttribute("href", href);
            }
            else
            {
                elem.removeAttribute("href");
                String attrClass = elem.getAttribute("class");
                if (attrClass == null || attrClass.isBlank())
                    attrClass = "removed";
                else
                    attrClass += " removed";
                elem.setAttribute("class", attrClass);
                System.out.printf("[html] Removing unknown href : %s%n", rawHref);
            }
        }
    }

    private Path getHtmlFile(String subpath)
    {
        Path html;

        html = rootDir.resolve(subpath);
        if (Files.exists(html))
            return html;

        html = rootDir.resolve(subpath + ".html");
        if (Files.exists(html))
            return html;

        String spaced = subpath.replace('_', ' ');
        html = rootDir.resolve(spaced);
        if (Files.exists(html))
            return html;

        return rootDir.resolve(spaced + ".html");
    }

    private Document loadHtml(Path htmlFile) throws IOException, SAXException
    {
        // System.out.printf("[html] loading %s%n", htmlFile);
        try (BufferedReader reader = Files.newBufferedReader(htmlFile, StandardCharsets.UTF_8))
        {
            InputSource inputSource = new InputSource(reader);
            return domBuilder.parse(inputSource);
        }
    }

    private void cleanupTextNodes(Document dom) throws XPathExpressionException
    {
        for (Node node : xpathNodes(dom, "//text()"))
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
                    rawText = rawText.replaceAll("[" + nbsp + " ]", " ");
                    node.setTextContent(rawText);
                }
            }
        }
    }

    private void preserveSpace(Document dom) throws XPathExpressionException
    {
        for (Node node : xpathNodes(dom, "//pre"))
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
        List<Node> nodes = xpathNodes(dom, xpathToNode);
        if (!nodes.isEmpty())
        {
            System.out.printf("[html] removeNodes[%s] : %s%n", xpathToNode, relativeHtml);
            for (Node node : nodes)
            {
                node.getParentNode().removeChild(node);
            }
        }
    }

    private static boolean isElement(Node node)
    {
        return (node.getNodeType() == Node.ELEMENT_NODE);
    }

    private static Element toElement(Node node)
    {
        Element elem = (Element)node;
        return elem;
    }

    private Stream<Node> xpathStream(Document doc, String xpathExpr) throws XPathExpressionException
    {
        return xpathNodes(doc, xpathExpr).stream();
    }

    private Element xpathFirstElement(Document doc, String xpathExpr) throws XPathExpressionException
    {
        Node node = (Node)xPath.compile(xpathExpr).evaluate(doc, XPathConstants.NODE);
        if (node == null || node.getNodeType() != Node.ELEMENT_NODE)
            return null;
        return (Element)node;
    }

    private List<Node> xpathNodes(Document doc, String xpathExpr) throws XPathExpressionException
    {
        NodeList nodeList = (NodeList)xPath.compile(xpathExpr).evaluate(doc, XPathConstants.NODESET);
        return toList(nodeList);
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
