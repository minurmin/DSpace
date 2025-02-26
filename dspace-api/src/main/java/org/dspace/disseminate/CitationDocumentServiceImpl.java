/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.disseminate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.lowagie.text.pdf.AcroFields;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfImportedPage;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.BitstreamService;
import org.dspace.content.service.CommunityService;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;
import org.dspace.disseminate.service.CitationDocumentService;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Citation Document produces a dissemination package (DIP) that is different that the archival package (AIP).
 * In this case we append the descriptive metadata to the end (configurable) of the document. i.e. last page of PDF.
 * So instead of getting the original PDF, you get a cPDF (with citation information added).
 *
 * Template-based implementation (method addCoverPageBytes, formerly part of a class "CoverPageStamper") uses
 * PDFStamper from OpenPDF libray to add extra page to existing pdfs on the fly. It is configured in citation-page.cfg
 * by adding a file path to citation-page.template_path. Path must point to a PDF form that works as the cover page.
 * Metadata fields are compared to the field names found in the PDF form based on the following rules:
 *  - community or collection are used as special names to insert owning community or collection name
 *  - "|" can be used as separator to provide multiple source fields (values from first nonempty one are used)
 *  - otherwise, field names with typical schema.element.qualifier format use used
 * Matching fields are populated with the metadata value. If there are multiple occurrences of the same field
 * "; " is used as a separator. Finally, the form is prepended to the original pdf. Encrypted PDFs will be skipped.
 *
 * @author Peter Dietz (peter@longsight.com)
 * @author Joonas Kesäniemi (original template-based implementation)
 * @author Miika Nurminen
 */
public class CitationDocumentServiceImpl implements CitationDocumentService, InitializingBean {
    /**
     * Class Logger
     */
    private static final Logger log = LogManager.getLogger(CitationDocumentServiceImpl.class);

    /**
     * A set of MIME types that can have a citation page added to them. That is,
     * MIME types in this set can be converted to a PDF which is then prepended
     * with a citation page.
     */
    protected final Set<String> VALID_TYPES = new HashSet<>(2);

    /**
     * A set of MIME types that refer to a PDF
     */
    protected final Set<String> PDF_MIMES = new HashSet<>(2);

    /**
     * A set of MIME types that refer to a JPEG, PNG, or GIF
     */
    protected final Set<String> RASTER_MIMES = new HashSet<>();
    /**
     * A set of MIME types that refer to a SVG
     */
    protected final Set<String> SVG_MIMES = new HashSet<>();

    /**
     * List of all enabled collections, inherited/determined for those under communities.
     */
    protected List<String> citationEnabledCollectionsList;

    /**
     * Path to CoverPage template
     */
    protected String coverPagePath;

    /**
     * Field names (if any) that need to be escaped for HTML code (assuming that output is PDF is text only)
     */
    protected List<String> htmlfields;


    @Autowired(required = true)
    protected AuthorizeService authorizeService;
    @Autowired(required = true)
    protected BitstreamService bitstreamService;
    @Autowired(required = true)
    protected CommunityService communityService;
    @Autowired(required = true)
    protected ItemService itemService;
    @Autowired(required = true)
    protected ConfigurationService configurationService;

    @Autowired(required = true)
    protected HandleService handleService;

    @Override
    public void afterPropertiesSet() throws Exception {
        // Add valid format MIME types to set. This could be put in the Schema
        // instead.
        //Populate RASTER_MIMES
        SVG_MIMES.add("image/jpeg");
        SVG_MIMES.add("image/pjpeg");
        SVG_MIMES.add("image/png");
        SVG_MIMES.add("image/gif");
        //Populate SVG_MIMES
        SVG_MIMES.add("image/svg");
        SVG_MIMES.add("image/svg+xml");


        //Populate PDF_MIMES
        PDF_MIMES.add("application/pdf");
        PDF_MIMES.add("application/x-pdf");

        //Populate VALID_TYPES
        VALID_TYPES.addAll(PDF_MIMES);

        // Global enabled?
        citationEnabledGlobally = configurationService.getBooleanProperty("citation-page.enable_globally", false);

        //Load enabled collections
        String[] citationEnabledCollections = configurationService
            .getArrayProperty("citation-page.enabled_collections");
        citationEnabledCollectionsList = Arrays.asList(citationEnabledCollections);

        //Load enabled communities, and add to collection-list
        String[] citationEnabledCommunities = configurationService
            .getArrayProperty("citation-page.enabled_communities");
        if (citationEnabledCollectionsList == null) {
            citationEnabledCollectionsList = new ArrayList<>();
        }

        if (citationEnabledCommunities != null && citationEnabledCommunities.length > 0) {
            Context context = null;
            try {
                context = new Context();
                for (String communityString : citationEnabledCommunities) {
                    DSpaceObject dsoCommunity = handleService.resolveToObject(context, communityString.trim());
                    if (dsoCommunity instanceof Community) {
                        Community community = (Community) dsoCommunity;
                        List<Collection> collections = communityService.getAllCollections(context, community);

                        for (Collection collection : collections) {
                            citationEnabledCollectionsList.add(collection.getHandle());
                        }
                    } else {
                        log.error(
                            "Invalid community for citation.enabled_communities, value:" + communityString.trim());
                    }
                }
            } catch (SQLException e) {
                log.error(e.getMessage());
            } finally {
                if (context != null) {
                    context.abort();
                }
            }
        }

        citationAsFirstPage = configurationService.getBooleanProperty("citation-page.citation_as_first_page", true);

        // Fields related to configuration of pdf template-based implementation
        coverPagePath = configurationService.getProperty("citation-page.template_path");
        htmlfields = Arrays.asList(configurationService.getArrayProperty("citation-page.htmlfields"));
    }


    protected CitationDocumentServiceImpl() {
    }

    /**
     * Boolean to determine is citation-functionality is enabled globally for entire site.
     * config/module/citation-page: enable_globally, default false. true=on, false=off
     */
    protected Boolean citationEnabledGlobally = null;

    protected boolean isCitationEnabledGlobally() {
        return citationEnabledGlobally;
    }

    protected boolean isCitationEnabledThroughCollection(Context context, Bitstream bitstream) throws SQLException {
        //Reject quickly if no-enabled collections
        if (citationEnabledCollectionsList.isEmpty()) {
            return false;
        }

        DSpaceObject owningDSO = bitstreamService.getParentObject(context, bitstream);
        if (owningDSO instanceof Item) {
            Item item = (Item) owningDSO;

            List<Collection> collections = item.getCollections();

            for (Collection collection : collections) {
                if (citationEnabledCollectionsList.contains(collection.getHandle())) {
                    return true;
                }
            }
        }

        // If previous logic didn't return true, then we're false
        return false;
    }

    @Override
    public Boolean isCitationEnabledForBitstream(Bitstream bitstream, Context context) throws SQLException {
        if (isCitationEnabledGlobally() || isCitationEnabledThroughCollection(context, bitstream)) {

            // Bitstreams in DISPLAY bundle should already have a citation page
            for (Bundle bundle : bitstream.getBundles()) {
                // Should be same as DISPLAY_BUNDLE_NAME in CitationPage curation task
                if (bundle.getName().equals("DISPLAY")) {
                    return false;
                }
            }

            boolean adminUser = authorizeService.isAdmin(context);

            if (!adminUser && canGenerateCitationVersion(context, bitstream)) {
                return true;
            }
        }

        // If previous logic didn't return true, then we're false.
        return false;
    }

    /**
     * Should the citation page be the first page of the document, or the last page?
     * default = true. true = first page, false = last page
     * citation_as_first_page=true
     */
    protected Boolean citationAsFirstPage = null;

    protected Boolean isCitationFirstPage() {
        return citationAsFirstPage;
    }

    @Override
    public boolean canGenerateCitationVersion(Context context, Bitstream bitstream) throws SQLException {
        return VALID_TYPES.contains(bitstream.getFormat(context).getMIMEType());
    }

    protected byte[] addCoverPageBytes(InputStream orig, Item item) throws IOException {
        ByteArrayOutputStream bout = null;
        ByteArrayOutputStream coverStream = null;
        try {
            PdfReader originalReader = new PdfReader(orig);

            if (originalReader.isEncrypted()) {
                throw new IOException("Could not add coverpage to an encrypted pdf. Item handle:" + item.getHandle());
            }

            bout = new ByteArrayOutputStream();
            PdfStamper originalStamper = new PdfStamper(originalReader, bout);

            // load the template
            File templateFile = new File(coverPagePath);
            if (!templateFile.exists()) {
                originalStamper.close();
                throw new IOException("Could not find cover page template file using path " + coverPagePath);
            }

            PdfReader coverTemplateReader = new PdfReader(new FileInputStream(templateFile));
            coverStream = new ByteArrayOutputStream();

            PdfStamper coverStamper = new PdfStamper(coverTemplateReader,
                    coverStream);
            AcroFields fields = coverStamper.getAcroFields();

            Map<String, com.lowagie.text.pdf.AcroFields.Item> fieldMap = fields.getAllFields();
            Iterator<String> i = fieldMap.keySet().iterator();
            String key;
            java.util.List<MetadataValue> values;

            while (i.hasNext()) {
                key = i.next();
                // special handling to insert community or collection name
                if (key.equals("community")) {
                    fields.setField(key, getOwningCommunity(null, item));
                    continue;
                } else if (key.equals("collection")) {
                    fields.setField(key, getOwningCollection(item));
                    continue;
                } else {
                    // "|" can be used as separator to provide multiple source fields.
                    String[] token = key.split("\\|");
                    // First existing and nonempty value will be used
                    boolean fieldAdded = false;
                    for (String fieldname : token) {
                        try {
                            if (fieldAdded) {
                                break;
                            }
                            values = itemService.getMetadataByMetadataString(item, fieldname.strip());
                            if (values != null && values.size() > 0) {
                                String text = "";
                                int numOfValues = values.size();
                                for (int k = 0; k < numOfValues; k++) {
                                    String textToAdd = "";
                                    // skip empty, whitespace, or null values
                                    // removing html tags if field is in htmlfields list
                                    if (htmlfields.contains(fieldname)) {
                                        textToAdd = StringEscapeUtils
                                                .unescapeHtml(values.get(k).getValue().replaceAll("<[^ ][^>]*>", ""));
                                    } else {
                                        textToAdd = StringEscapeUtils.unescapeHtml(values.get(k).getValue());
                                    }
                                    if (StringUtils.isBlank(textToAdd)) {
                                        continue;
                                    } else {
                                        text = text + textToAdd;
                                        fieldAdded = true;
                                    }
                                    // if multiple occurrences (e.g. dc.contributor.author), separate by "; "
                                    if (k < (numOfValues - 1)) {
                                        text = text + "; ";
                                    }
                                }
                                fields.setField(key, text);
                                break;
                            }
                        } catch (Exception e) {
                            // e.g. invalid name (getMetadataByMetadataString assumes schema.element.qualifier format)
                            log.error("Error in processing field " + fieldname + " for item " + item.getHandle());
                        }
                    }
                }
            }
            coverStamper.setFormFlattening(true);
            // cover page is ready
            coverStamper.close();
            coverTemplateReader.close();

            coverStream.flush();

            PdfContentByte cb;
            PdfReader coverReader = new PdfReader(coverStream.toByteArray());
            PdfImportedPage importedCoverpage = originalStamper
                    .getImportedPage(coverReader, 1);

            if (isCitationFirstPage()) {
                //citation as cover page
                originalStamper.insertPage(1, coverReader.getPageSize(1));
                cb = originalStamper.getUnderContent(1);
            } else {
                //citation as tail page
                originalStamper.insertPage(originalReader.getNumberOfPages() + 1, coverReader.getPageSize(1));
                cb = originalStamper.getUnderContent(originalReader.getNumberOfPages());
            }

            cb.addTemplate(importedCoverpage, 0, 0);

            originalStamper.close();
            bout.flush();

            return bout.toByteArray();

        } finally {

            if (coverStream != null) {
                try {
                    coverStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (bout != null) {
                try {
                    bout.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public Pair<byte[], Long> makeCitedDocument(Context context, Bitstream bitstream)
            throws IOException, SQLException, AuthorizeException {
        InputStream inputStream = null;
        try {
            Item item = (Item) bitstreamService.getParentObject(context, bitstream);
            inputStream = bitstreamService.retrieve(context, bitstream);
            byte[] data = addCoverPageBytes(inputStream, item);
            return Pair.of(data, Long.valueOf(data.length));
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    @Override
    public String getOwningCommunity(Context context, Item item) {
        try {
            List<Community> comms = itemService.getCommunities(context, item);
            if (comms.size() > 0) {
                return comms.get(0).getName();
            } else {
                return " ";
            }

        } catch (SQLException e) {
            log.error(e.getMessage());
            return e.getMessage();
        }
    }

    @Override
    public String getOwningCollection(Item item) {
        return item.getOwningCollection().getName();
    }
}
