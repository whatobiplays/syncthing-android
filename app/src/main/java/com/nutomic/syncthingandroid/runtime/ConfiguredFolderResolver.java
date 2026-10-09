package com.nutomic.syncthingandroid.runtime;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Resolves the path of one configured folder from the authoritative configuration document.
 *
 * <p>Folder operations accept a folder identifier and re-resolve its path every time they run.
 * The configuration document is the only authority for that path: a path held by a caller, or by
 * the in-memory configuration projection, can be stale or forged and must never decide which
 * files a privileged operation touches.</p>
 *
 * <p>Parsing is minimal and hardened. Only top-level {@code <folder>} elements are considered, so
 * defaults and nested nodes cannot shadow a configured folder, and DTDs, external entities, and
 * XInclude are refused so a configuration document cannot pull in another file.</p>
 */
final class ConfiguredFolderResolver {
    private ConfiguredFolderResolver() {
    }

    /**
     * Returns the absolute path configured for one folder identifier.
     *
     * @param configuration authoritative configuration document content
     * @param folderId      identifier of the folder to resolve
     * @param tildeBase     absolute path a leading {@code ~} expands to
     * @throws FolderOperationException when the document cannot be used, or when no configured
     *                                  folder carries the identifier
     */
    static String resolveFolderPath(byte[] configuration, String folderId, String tildeBase)
            throws FolderOperationException {
        Objects.requireNonNull(folderId, "The folder identifier is required");
        if (configuration == null || configuration.length == 0) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                    "The authoritative configuration is not present"
            );
        }
        Document document = parse(configuration);
        Element configured = findFolder(document, folderId);
        if (configured == null) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_NOT_CONFIGURED,
                    "No configured folder carries the identifier " + folderId
            );
        }
        String configuredPath = configured.getAttribute("path");
        if (configuredPath.isEmpty()) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                    "The configured folder " + folderId + " has no path"
            );
        }
        String resolved = expandTilde(configuredPath, tildeBase);
        if (resolved.isEmpty() || resolved.charAt(0) != '/') {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                    "The configured folder " + folderId + " does not use an absolute path"
            );
        }
        return resolved;
    }

    /** Parses one configuration document with external references refused. */
    private static Document parse(byte[] configuration) throws FolderOperationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (ParserConfigurationException unsupported) {
            // Older parsers do not know the feature; the entity resolver below still refuses
            // every external reference.
        }
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not allowed in the configuration");
            });
            Document document = builder.parse(new ByteArrayInputStream(configuration));
            if (document.getDocumentElement() == null) {
                throw new SAXException("The configuration has no document element");
            }
            return document;
        } catch (ParserConfigurationException | SAXException | IOException malformed) {
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                    "The authoritative configuration could not be parsed",
                    malformed
            );
        }
    }

    /** Returns the top-level folder element with the requested identifier, or {@code null}. */
    private static Element findFolder(Document document, String folderId) {
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node.getNodeType() != Node.ELEMENT_NODE || !"folder".equals(node.getNodeName())) {
                continue;
            }
            Element folder = (Element) node;
            if (folderId.equals(folder.getAttribute("id"))) {
                return folder;
            }
        }
        return null;
    }

    /**
     * Expands a leading tilde exactly the way the configuration readers do.
     *
     * <p>Only a bare {@code ~} and a {@code ~/...} prefix name the home directory. Any other path
     * that merely starts with a tilde is left untouched, so it is refused later as a relative path
     * instead of being aimed at a different directory below the home path.</p>
     *
     * @throws FolderOperationException when the path is home-relative and no absolute home path
     *                                  is known
     */
    private static String expandTilde(String path, String tildeBase)
            throws FolderOperationException {
        if (!path.equals("~") && !path.startsWith("~/")) {
            return path;
        }
        if (tildeBase == null || tildeBase.isEmpty() || tildeBase.charAt(0) != '/') {
            // Expanding "~" without a known absolute base would silently turn "~/folder" into
            // an unrelated absolute path, so the document is refused instead.
            throw new FolderOperationException(
                    FolderOperationFailure.FOLDER_CONFIGURATION_UNREADABLE,
                    "The configured folder uses a relative home path that cannot be expanded"
            );
        }
        return tildeBase + path.substring(1);
    }
}
