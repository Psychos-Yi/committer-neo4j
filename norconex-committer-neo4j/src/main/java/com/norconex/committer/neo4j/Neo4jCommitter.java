/* Copyright 2019 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.committer.neo4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;

import com.norconex.committer.core.AbstractCommitter;
import com.norconex.committer.core.AbstractMappedCommitter;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.IAddOperation;
import com.norconex.committer.core.ICommitOperation;
import com.norconex.committer.core.IDeleteOperation;
import com.norconex.committer.neo4j.Relationship.Direction;
import com.norconex.committer.neo4j.topologies.NoContentTopology;
import com.norconex.committer.neo4j.topologies.NodeTopology;
import com.norconex.committer.neo4j.topologies.OneNodeTopology;
import com.norconex.committer.neo4j.topologies.SplittedTopology;
import com.norconex.commons.lang.encrypt.EncryptionKey;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.xml.EnhancedXMLStreamWriter;

/**
 * <p>
 * Commit documents to a Neo4j graph database.
 * </p>
 *
 * <dl>
 *   <dt>targetReferenceField</dt>
 *   <dd>
 *     The field that will hold your document reference. Default is
 *     {@value #DEFAULT_NEO4J_ID_FIELD} and can be overwritten with
 *     {@link #setTargetReferenceField(String)}.
 *   </dd>
 *
 *   <dt>targetContentField</dt>
 *   <dd>
 *     The field that will hold your document content (or "body"). Default is
 *     {@value #DEFAULT_NEO4J_CONTENT_FIELD} and can be overwritten with
 *     {@link #setTargetContentField(String)}.
 *   </dd>
 * </dl>
 *
 * <h3>Authentication</h3>
 * <p>
 * For databases requiring authentication, the <code>password</code> can
 * optionally be encrypted using {@link EncryptionUtil} (or command-line
 * "encrypt.bat" or "encrypt.sh"). In order for the password to be decrypted
 * properly, you need to specify the encryption key used to encrypt it. The key
 * can be stored in a few supported locations and a combination of
 * <code>passwordKey</code> and <code>passwordKeySource</code> must be specified
 * to properly locate the key. The supported sources are:
 * </p>
 *
 * <table border="1" summary="">
 *   <tr>
 *     <th><code>passwordKeySource</code></th>
 *     <th><code>passwordKey</code></th>
 *   </tr>
 *   <tr>
 *     <td><code>key</code></td>
 *     <td>The actual encryption key.</td>
 *   </tr>
 *   <tr>
 *     <td><code>file</code></td>
 *     <td>Path to a file containing the encryption key.</td>
 *   </tr>
 *   <tr>
 *     <td><code>environment</code></td>
 *     <td>Name of an environment variable containing the key.</td>
 *   </tr>
 *   <tr>
 *     <td><code>property</code></td>
 *     <td>Name of a JVM system property containing the key.</td>
 *   </tr>
 * </table>
 *
 * <h3>XML configuration usage:</h3>
 *
 * <pre>
 *  &lt;committer class="com.norconex.committer.neo4j.Neo4jCommitter"&gt;
 *      &lt;!-- Mandatory settings --&gt;
 *      &lt;uri&gt;
 *        (Connection Uri like bolt://localhost:7687)
 *      &lt;/uri&gt;
 *      &lt;user&gt;
 *        (The Neo4j username)
 *      &lt;/user&gt;
 *      &lt;password&gt;
 *        (The Neo4j password)
 *      &lt;/password&gt;
 *      &lt;authentType&gt;
 *        (Only BASIC is supported for now)
 *      &lt;/authentType&gt;
 *      &lt;multiValuesJoiner&gt;
 *        (One or more characters to join multi-value fields. Default is "|".)
 *      &lt;/multiValuesJoiner&gt;
 *
 *      &lt;!-- Other settings --&gt;
 *      &lt;nodeTopology&gt;
 *        &lt;!--
 *          The structure of a node for a committed document. Possible values:
 *
 *             ONE_NODE:   Default. Creates a node with metadata and content.
 *             NO_CONTENT: Creates a node without content.
 *             SPLITTED:   Creates three nodes, one main node with the ID for
 *                         the committed document, one with the content
 *                         (linked to the main node) and another with metadata
 *                         also linked to the main node.)
 *          --&gt;
 *        [ONE_NODE|NO_CONTENT|SPLITTED]
 *      &lt;/nodeTopology&gt;
 *      &lt;primaryLabel&gt;
 *        (Primary label name used for all created nodes)
 *      &lt;/primaryLabel&gt;
 *
 *      &lt;relationships&gt;
 *        &lt;!--
 *          Relationships are used to create relationships
 *          between nodes. If a source or target does not exist,
 *          it will be created automatically.
 *          --&gt;
 *          &lt;relationship type="(type of the relationship)"
 *              direction="[NONE|INCOMING|OUTGOING|BOTH]"&gt;
 *            &lt;sourcePropertyKey&gt;
 *              (a metadata field)
 *            &lt;/sourcePropertyKey&gt;
 *            &lt;targetPropertyKey&gt;
 *              (a metadata field)
 *            &lt;/targetPropertyKey&gt;
 *          &lt;/relationship&gt;
 *      &lt;/relationships&gt;
 *
 *      &lt;additionalLabels&gt;
 *      &lt;!--
 *        It is possible to add labels on a new created node. To do that,
 *        specify a metadata field or many by adding sourceField elements.
 *        --&gt;
 *        &lt;sourceField keep="[false|true]"&gt;
 *          (metadata field)
 *        &lt;/sourceField&gt;
 *      &lt;/additionalLabels&gt;
 *
 *      &lt;!-- Use the following if password is encrypted. --&gt;
 *      &lt;passwordKey&gt;
 *        (the encryption key or a reference to it)
 *      &lt;/passwordKey&gt;
 *      &lt;passwordKeySource&gt;
 *        [key|file|environment|property]
 *      &lt;/passwordKeySource&gt;
 *
 *      &lt;sourceReferenceField keep="[false|true]"&gt;
 *         (Optional name of field that contains the document reference, when
 *          the default document reference is not used.
 *          Once re-mapped, this metadata source field is
 *          deleted, unless "keep" is set to <code>true</code>.)
 *      &lt;/sourceReferenceField&gt;
 *      &lt;targetReferenceField&gt;
 *         (Name of the database target field where the store a document unique
 *          identifier (sourceReferenceField).  If not specified,
 *          default is "id". Typically is a tableName primary key.)
 *      &lt;/targetReferenceField&gt;
 *      &lt;sourceContentField keep="[false|true]"&gt;
 *         (If you wish to use a metadata field to act as the document
 *          "content", you can specify that field here.  Default
 *          does not take a metadata field but rather the document content.
 *          Once re-mapped, the metadata source field is deleted,
 *          unless "keep" is set to <code>true</code>.)
 *      &lt;/sourceContentField&gt;
 *      &lt;targetContentField&gt;
 *         (Target field name for a document content/body.
 *          Default is "content".)
 *      &lt;/targetContentField&gt;
 *      &lt;queueDir&gt;(optional path where to queue files)&lt;/queueDir&gt;
 *      &lt;queueSize&gt;(max queue size before committing)&lt;/queueSize&gt;
 *      &lt;commitBatchSize&gt;
 *         (Max number of documents to send to the database at once.)
 *      &lt;/commitBatchSize&gt;
 *      &lt;maxRetries&gt;(max retries upon commit failures)&lt;/maxRetries&gt;
 *      &lt;maxRetryWait&gt;
 *        (max delay in milliseconds between retries)
 *      &lt;/maxRetryWait&gt;
 *  &lt;/committer&gt;
 * </pre>
 *
 *
 * <h4>Usage example:</h4>
 * <p>
 * The following example creates a graph of crawl in combination with
 * HttpCollector.  It will also add an addtional label taking its source
 * from a sample "TYPE" field.
 * </p>
 *
 * <pre>
 *  &lt;committer class="com.norconex.committer.neo4j.Neo4jCommitter"&gt;
 *    &lt;uri&gt;bolt://localhost:7687&lt;/uri&gt;
 *    &lt;user&gt;neo4j&lt;/user&gt;
 *    &lt;password&gt;
 *      AcwFJPHITfk6LrRp7HW7Ag6hvDZotXcvWt2WvDMcGIo=
 *    &lt;/password&gt;
 *    &lt;authentType&gt;BASIC&lt;/authentType&gt;
 *    &lt;passwordKey&gt;key.txt&lt;/passwordKey&gt;
 *    &lt;passwordKeySource&gt;file&lt;/passwordKeySource&gt;
 *
 *    &lt;multiValuesJoiner&gt;|&lt;/multiValuesJoiner&gt;
 *    &lt;nodeTopology&gt;NO_CONTENT&lt;/nodeTopology&gt;
 *    &lt;primaryLabel&gt;WINEDB&lt;/primaryLabel&gt;
 *
 *    &lt;relationships&gt;
 *      &lt;relationship type="LINKED_TO" direction="OUTGOING"&gt;
 *        &lt;sourcePropertyKey&gt;
 *          collector.referenced-urls
 *        &lt;/sourcePropertyKey&gt;
 *        &lt;targetPropertyKey&gt;document.reference&lt;/targetPropertyKey&gt;
 *      &lt;/relationship&gt;
 *    &lt;/relationships&gt;
 *    &lt;additionalLabels&gt;
 *      &lt;sourceField keep="false"&gt;TYPE&lt;/sourceField&gt;
 *    &lt;/additionalLabels&gt;
 *  &lt;/committer&gt;
 * </pre>
 *
 * @author Sylvain Roussy
 */
public class Neo4jCommitter extends AbstractMappedCommitter {

    private static final Logger LOGGER = LogManager
            .getLogger(Neo4jCommitter.class);

    /** XML element name for multi values joiner */
    public static final String CONFIG_KEY_MULTI_VALUES_JOINER =
            "multiValuesJoiner";
    /** XML element name for primary label */
    public static final String CONFIG_KEY_PRIMARY_LABEL = "primaryLabel";
    /** XML element name for node topology */
    public static final String CONFIG_KEY_NODE_TOPOLOGY = "nodeTopology";
    /** XML element name for Neo4j uri */
    public static final String CONFIG_KEY_URI = "uri";
    /** XML element name for Neo4j password */
    public static final String CONFIG_KEY_PASSWORD = "password";
    /** XML element name for Neo4j user */
    public static final String CONFIG_KEY_USER = "user";
    /** XML element name for relationship target property key */
    public static final String CONFIG_KEY_TARGET_PROPERTY_KEY =
            "targetPropertyKey";
    /** XML element name for relationship source property key */
    public static final String CONFIG_KEY_SOURCE_PROPERTY_KEY =
            "sourcePropertyKey";

    public static final String ATTRIBUTE_KEY_DIRECTION = "[@direction]";
    public static final String ATTRIBUTE_KEY_TYPE = "[@type]";
    public static final String ATTRIBUTE_KEY_KEEP = "[@keep]";
    public static final String ATTRIBUTE_KEY_TARGET_FIND_SYNTAX =
            "[@targetFindSyntax]";

    /** Default Identifier property name for Neo4j nodes */
    public static final String DEFAULT_NEO4J_ID_FIELD = "identity";
    /** Default content property name for Neo4j nodes */
    public static final String DEFAULT_NEO4J_CONTENT_FIELD = "content";
    /** Default relationship type for relationships */
    public static final String DEFAULT_NEO4J_PARENT_LINK = "PARENT_OF";
    /** Default primary label name for Neo4j nodes */
    public static final String DEFAULT_NEO4J_PRIMARY_LABEL =
            "CommittedDocument";
    /** Default property name for target in relationships */
    public static final String DEFAULT_NEO4J_TARGET_FIND_SYNTAX =
            "document.reference";
    /** Default relationship direction */
    public static final Direction DEFAULT_NEO4J_RELS_DIRECTION = Direction.NONE;
    /** Default separator for multi-values in a Neo4j property */
    public static final String DEFAULT_MULTI_VALUES_JOINER = "|";
    /** Default property name for source in relationships */
    public static final String DEFAULT_NEO4J_SOURCE_PROPERTY_KEY =
            "collector.referrer-reference";
    /** Default property name for target in relationships */
    public static final String DEFAULT_NEO4J_TARGET_PROPERTY_KEY =
            "document.reference";

    public static final String DEFAULT_SOURCE_REFERENCE_FIELD =
            "document.reference";

    /**
     * Neo4j Authent type, only BASIC is supported for now.
     */
    public enum AuthType {
        BASIC
    }

    /**
     * This enumeration specify how the nodes must be built.
     * <ul>
     * <li>ONE_NODE : a node for one committed document with metadata and
     * content</li>
     * <li>NO_CONTENT : a node for one committed document with metadata
     * only</li>
     * <li>SPLITTED : three nodes for one committed document, one with
     * identifier, one with metadata and one with content</li>
     * </ul>
     */
    public enum TopologyType {
        ONE_NODE, SPLITTED, NO_CONTENT
    }

    /** Instance of Neo4j Driver */
    private transient Driver neo4jDriver;
    /** Neo4j user */
    private String user;
    /** Neo4j password */
    private String password;
    private EncryptionKey passwordKey;
    /** Neo4j Uri to connect to */
    private String uri;

    private String primaryLabel = DEFAULT_NEO4J_PRIMARY_LABEL;
    private String multiValuesJoiner = DEFAULT_MULTI_VALUES_JOINER;
    private final List<AdditionalLabel> additionalLabels = new ArrayList<>();
    private final List<Relationship> relationships = new ArrayList<>();


    /** Topology used to build the nodes into the graph */
    private TopologyType topologyType = TopologyType.ONE_NODE;
    /** Instance of the Topology */
    private NodeTopology nodeTopologyInstance;
    /** Authentification type used to connect to Neo4j */
    private final AuthType authentType = AuthType.BASIC;


    /**
     * Returns the label used for all nodes imported into Neo4j with the committer
     * @return primaryLabel primary label
     */
    public String getPrimaryLabel() {
        return primaryLabel;
    }
    /**
     * Sets  the label used for all nodes imported into Neo4j with the committer
     * @param primaryLabel primary label
     */
    public void setPrimaryLabel(String primaryLabel) {
        this.primaryLabel = primaryLabel;
    }

    public synchronized Driver getNeo4jDriver() {
        if (neo4jDriver == null) {
            loadNeo4jDriver();
        }
        return neo4jDriver;
    }

    public String getUser() {
        return user;
    }
    public void setUser(String user) {
        this.user = user;
        this.neo4jDriver = null;
    }

    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
        this.neo4jDriver = null;
    }

    public String getUri() {
        return uri;
    }
    public void setUri(String uri) {
        this.uri = uri;
        this.neo4jDriver = null;
    }

    public TopologyType getTopologyType() {
        return topologyType;
    }
    public void setTopologyType(TopologyType typology) {
        this.topologyType = typology;
    }

    public EncryptionKey getPasswordKey() {
        return passwordKey;
    }
    public void setPasswordKey(EncryptionKey passwordKey) {
        this.passwordKey = passwordKey;
        this.neo4jDriver = null;
    }

    public String getMultiValuesJoiner() {
        return multiValuesJoiner;
    }
    public void setMultiValuesJoiner(String multiValuesJoiner) {
        this.multiValuesJoiner = multiValuesJoiner;
    }

    /**
     * Gets additional labels.
     * @return additional labels
     */
    public List<AdditionalLabel> getAdditionalLabels() {
        return additionalLabels;
    }
    /**
     * Sets additional labels.
     * @param additionalLabels additional labels
     */
    public void setAdditionalLabels(List<AdditionalLabel> additionalLabels) {
        this.additionalLabels.clear();
        this.additionalLabels.addAll(additionalLabels);
    }
    /**
     * Adds additional label.
     * @param additionalLabel additional label
     */
    public void addAdditionalLabel(AdditionalLabel additionalLabel) {
        this.additionalLabels.add(additionalLabel);
    }


    public List<Relationship> getRelationships() {
        return relationships;
    }
    public void setRelationships(List<Relationship> relationships) {
        this.relationships.clear();
        this.relationships.addAll(relationships);
    }
    public void addRelationship(Relationship relationship) {
        this.relationships.add(relationship);
    }

    @Override
    protected void saveToXML(XMLStreamWriter writer) throws XMLStreamException {
        EnhancedXMLStreamWriter w = new EnhancedXMLStreamWriter(writer);
        w.writeElementString(CONFIG_KEY_USER, this.getUser());
        w.writeElementString(CONFIG_KEY_PASSWORD, this.getPassword());
        w.writeElementString(CONFIG_KEY_URI, this.getUri());
        w.writeElementString(
                CONFIG_KEY_NODE_TOPOLOGY, getTopologyType().name());
        w.writeElementString(CONFIG_KEY_PRIMARY_LABEL, getPrimaryLabel());
        w.writeElementString(
                CONFIG_KEY_MULTI_VALUES_JOINER, getMultiValuesJoiner());

        w.writeStartElement(("additionalLabels"));
        final List<AdditionalLabel> labels = getAdditionalLabels();
        for (AdditionalLabel additionalLabel : labels) {
            w.writeStartElement("sourceField");

            w.writeAttributeBoolean("keep", additionalLabel.isKeep());
            w.writeCharacters(additionalLabel.getSourceField());
            w.writeEndElement();
        }
        w.writeEndElement();

        if (!getRelationships().isEmpty()) {
            w.writeStartElement(("relationships"));
            for (Relationship rel : getRelationships()) {
                w.writeStartElement("relationship");

                w.writeAttributeString("type", rel.getType());
                w.writeAttributeString("direction", rel.getDirection().name());
                w.writeAttributeString("targetFindSyntax",
                        rel.getTargetFindSyntax().name());

                w.writeStartElement("sourcePropertyKey");
                w.writeCharacters(rel.getSourcePropertyKey());
                w.writeEndElement();

                w.writeStartElement("targetPropertyKey");
                w.writeCharacters(rel.getTargetPropertyKey());
                w.writeEndElement();

                w.writeEndElement();
            }
            w.writeEndElement();
        }

        // Encrypted password:
        EncryptionKey key = getPasswordKey();
        if (key != null) {
            w.writeElementString("passwordKey", key.getValue());
            if (key.getSource() != null) {
                w.writeElementString("passwordKeySource",
                        key.getSource().name().toLowerCase());
            }
        }

    }

    @Override
    protected void loadFromXml(XMLConfiguration xml) {
        setUser(xml.getString(CONFIG_KEY_USER, getUser()));
        setPassword(xml.getString(CONFIG_KEY_PASSWORD, getPassword()));
        setUri(xml.getString(CONFIG_KEY_URI, getUri()));

        this.topologyType = TopologyType.valueOf(xml.getString(
                CONFIG_KEY_NODE_TOPOLOGY, getTopologyType().name()));

        setPrimaryLabel(
                xml.getString(CONFIG_KEY_PRIMARY_LABEL, getPrimaryLabel()));

        setMultiValuesJoiner(xml.getString(
                CONFIG_KEY_MULTI_VALUES_JOINER, getMultiValuesJoiner()));

        final List<HierarchicalConfiguration> xmlLabels = xml
                .configurationsAt("additionalLabels.sourceField");
        if (!xmlLabels.isEmpty()) {

            for (HierarchicalConfiguration xmlProp : xmlLabels) {
                final AdditionalLabel additionalLabel = new AdditionalLabel();
                additionalLabel.setKeep(
                        xmlProp.getBoolean(ATTRIBUTE_KEY_KEEP, true));
                additionalLabel.setSourceField(
                        xmlProp.getString("", "unknown"));
                addAdditionalLabel(additionalLabel);
            }
        }

        final List<HierarchicalConfiguration> xmlRel = xml
                .configurationsAt("relationships.relationship");
        if (!xmlRel.isEmpty()) {

            for (HierarchicalConfiguration xmlPropRel : xmlRel) {
                final Relationship rel = new Relationship();
                rel.setType(xmlPropRel.getString(ATTRIBUTE_KEY_TYPE,
                        DEFAULT_NEO4J_PARENT_LINK));
                rel.setSourcePropertyKey(
                        xmlPropRel.getString(CONFIG_KEY_SOURCE_PROPERTY_KEY,
                                DEFAULT_NEO4J_TARGET_PROPERTY_KEY));
                rel.setTargetPropertyKey(
                        xmlPropRel.getString(CONFIG_KEY_TARGET_PROPERTY_KEY,
                                DEFAULT_NEO4J_SOURCE_PROPERTY_KEY));
                rel.setDirection(Relationship.Direction.valueOf(
                        xmlPropRel.getString(ATTRIBUTE_KEY_DIRECTION,
                                Relationship.Direction.NONE.name())));
                rel.setTargetFindSyntax(Relationship.FindSyntax.valueOf(
                        xmlPropRel.getString(ATTRIBUTE_KEY_TARGET_FIND_SYNTAX,
                                Relationship.FindSyntax.MERGE.name())));
                addRelationship(rel);
            }
        }

        // encrypted password:
        final String xmlKey = xml.getString("passwordKey", null);
        final String xmlSource = xml.getString("passwordKeySource", null);
        if (StringUtils.isNotBlank(xmlKey)) {
            EncryptionKey.Source source = null;
            if (StringUtils.isNotBlank(xmlSource)) {
                source = EncryptionKey.Source.valueOf(xmlSource.toUpperCase());
            }
            setPasswordKey(new EncryptionKey(xmlKey, source));
        }

        this.loadNodeTopologyInstance();
    }

    @Override
    protected void commitBatch(List<ICommitOperation> batch) {

        for (ICommitOperation op : batch) {
            if (op instanceof IAddOperation) {
                appendAddOperation((IAddOperation) op);
            } else if (op instanceof IDeleteOperation) {
                appendDeleteOperation((IDeleteOperation) op);
            } else {
                close();
                throw new CommitterException("Unsupported operation:" + op);
            }
        }

    }

    private void appendAddOperation(IAddOperation add) {
        final Properties p = add.getMetadata();
        String id = p.getString(getSourceReferenceField());
        if (StringUtils.isBlank(id)) {
            id = add.getReference();
        }

        try {
            final GraphEntry entry = new GraphEntry(
                    id, p, add.getContentStream());

            this.nodeTopologyInstance.storeEntry(entry);
        } catch (IOException e) {
            throw new CommitterException(e);
        }

    }

    private void appendDeleteOperation(IDeleteOperation del) {

        String id = del.getReference();
        if (StringUtils.isBlank(id)) {
            id = del.getReference();
        }

        this.nodeTopologyInstance.deleteEntry(id);
    }

    private void loadNeo4jDriver() {
        switch (authentType) {
        case BASIC:
            loadDriverWithBasicAuth();
        }
    }

    private void loadNodeTopologyInstance() {
        switch (topologyType) {
        case ONE_NODE:
            nodeTopologyInstance = new OneNodeTopology(this);
            break;
        case SPLITTED:
            nodeTopologyInstance = new SplittedTopology(this);
            break;
        case NO_CONTENT:
            nodeTopologyInstance = new NoContentTopology(this);
            break;

        }
        LOGGER.info("loadGraphTopology () Node topology instance loaded: "
                + nodeTopologyInstance.getClass().getName());
    }

    @Override
    public void commit() {
        super.commit();
        closeIfDone();
    }

    // TODO The following is a workaround to not having
    // a close() method (or equivalent) on the Committers yet.
    // So we check that the caller is not itself, which means it should
    // be the parent framework, which should in theory, call this only
    // once. This is safe to do as the worst case scenario is that a new
    // client is re-created.
    // Remove this method once proper init/close is added to Committers
    private void closeIfDone() {
        StackTraceElement[] els = Thread.currentThread().getStackTrace();
        for (StackTraceElement el : els) {
            if (AbstractCommitter.class.getName().equals(el.getClassName())
                    && "commitIfReady".equals(el.getMethodName())) {
                return;
            }
        }
        close();
    }

    protected void close() {
        if (neo4jDriver != null) {
            neo4jDriver.close();
        }
    }

    private void loadDriverWithBasicAuth() {
        String pwd = EncryptionUtil.decrypt(getPassword(), getPasswordKey());
        neo4jDriver = GraphDatabase.driver(uri, AuthTokens.basic(user, pwd));
        LOGGER.info("loadDriverWithBasicAuth () Neo4j Driver loaded "
                + "with BASIC Auth");
    }
}