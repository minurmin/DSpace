/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.xoai.filter;

import java.time.Instant;
import java.time.temporal.ChronoField;

import com.lyncode.xoai.dataprovider.services.api.DateProvider;
import com.lyncode.xoai.dataprovider.services.impl.BaseDateProvider;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.dspace.xoai.data.DSpaceItem;
import org.dspace.xoai.filter.results.SolrFilterResult;

/**
 * @author Lyncode Development Team (dspace at lyncode dot com)
 */
public class DateUntilFilter extends DSpaceFilter {
    private static final DateProvider dateProvider = new BaseDateProvider();
    private final Instant date;

    public DateUntilFilter(Instant date) {
        // As this is an 'until' filter, ensure milliseconds are set to 999 (maximum value)
        this.date = date.with(ChronoField.MILLI_OF_SECOND, 999);
    }

    @Override
    public boolean isShown(DSpaceItem item) {
        if (!item.getDatestamp().toInstant().isAfter(date)) {
            return true;
        }
        return false;
    }

    @Override
    public SolrFilterResult buildSolrQuery() {
        // Tweak to set the milliseconds
        String format = dateProvider.format(java.util.Date.from(date)).replace("Z", ".999Z");
        // if date has timestamp of 00:00:00, switch it to refer to end of day
        if (format.substring(11, 19).equals("00:00:00")) {
            format = format.substring(0, 11) + "23:59:59" + format.substring(19);
        }
        return new SolrFilterResult("item.lastmodified:[* TO "
                                        + ClientUtils.escapeQueryChars(format) + "]");
    }

}
