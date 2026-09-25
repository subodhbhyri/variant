package com.tailor.engine.docx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * A namespace-aware, XXE-hardened DOM parser for OOXML parts. Used for
 * bullet detection (spec section 4) and editing (section 5), where a DOM
 * walk is required rather than regex — unlike font normalization (section 3),
 * which only rewrites attribute values and stays regex-based on purpose
 * (see FontNormalizer's javadoc).
 */
public final class SafeXml {

    private SafeXml() {
    }

    public static Document parse(byte[] xmlBytes) throws IOException {
        try {
            DocumentBuilder builder = newBuilder();
            return builder.parse(new ByteArrayInputStream(xmlBytes));
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("failed to parse XML part", e);
        }
    }

    private static DocumentBuilder newBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        return factory.newDocumentBuilder();
    }
}
