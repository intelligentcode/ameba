package ameba.db.ebean.internal;

import ameba.db.ebean.EbeanFeature;
import ameba.db.model.Finder;
import com.avaje.ebean.ExpressionList;
import com.avaje.ebean.FutureList;
import com.avaje.ebean.Query;
import com.avaje.ebean.text.PathProperties;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.message.MessageBodyWorkers;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

/**
 * @author icode
 */
public class EbeanModelWriter implements MessageBodyWriter<Object> {

    static String SELECTABLE_PARAM_NAME = "select";
    static String ORDER_BY_PARAM_NAME = "sort";
    static String MAX_ROWS_PARAM_NAME = "maxrows";
    static String FIRST_ROW_PARAM_NAME = "firstrow";
    static String WHERE_PARAM_NAME = "where";

    @Context
    private Configuration configuration;

    @Inject
    private Provider<MessageBodyWorkers> workers;

    @PostConstruct
    private void init() {
        final String selectableParamName = (String) configuration.getProperty(EbeanFeature.SELECTABLE_PARAM_NAME);
        SELECTABLE_PARAM_NAME = StringUtils.isNotBlank(selectableParamName) ? selectableParamName : SELECTABLE_PARAM_NAME;

        final String orderByParamName = (String) configuration.getProperty(EbeanFeature.ORDER_BY_PARAM_NAME);
        ORDER_BY_PARAM_NAME = StringUtils.isNotBlank(orderByParamName) ? orderByParamName : ORDER_BY_PARAM_NAME;

        final String maxRowsParamName = (String) configuration.getProperty(EbeanFeature.MAX_ROWS_PARAM_NAME);
        MAX_ROWS_PARAM_NAME = StringUtils.isNotBlank(maxRowsParamName) ? maxRowsParamName : MAX_ROWS_PARAM_NAME;

        final String firstRowParamName = (String) configuration.getProperty(EbeanFeature.FIRST_ROW_PARAM_NAME);
        FIRST_ROW_PARAM_NAME = StringUtils.isNotBlank(firstRowParamName) ? firstRowParamName : FIRST_ROW_PARAM_NAME;

        final String whereParamName = (String) configuration.getProperty(EbeanFeature.WHERE_PARAM_NAME);
        WHERE_PARAM_NAME = StringUtils.isNotBlank(whereParamName) ? whereParamName : WHERE_PARAM_NAME;

    }


    @Context
    private UriInfo uriInfo;

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return Finder.class.isAssignableFrom(type)
                || Query.class.isAssignableFrom(type)
                || ExpressionList.class.isAssignableFrom(type)
                || FutureList.class.isAssignableFrom(type);
    }

    @Override
    public long getSize(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return -1;
    }

    /**
     * parse uri
     * <p/>
     * e.g.
     * <p/>
     * ?select=id,name,props(p1,p2,p3)
     * <p/>
     * ?select=(id,name,props(p1,p2,p3))
     *
     * @param query query
     */
    protected static void applyPathProperties(MultivaluedMap<String, String> queryParams, Query query) {
        List<String> selectables = queryParams.get(SELECTABLE_PARAM_NAME);
        if (selectables != null)
            for (String s : selectables) {
                PathProperties pathProperties = PathProperties.parse(s);
                pathProperties.apply(query);
            }
    }

    /**
     * Return a single Integer parameter.
     */
    protected static Integer getSingleIntegerParam(List<String> list) {
        String s = getSingleParam(list);
        if (s != null) {
            try {
                return Integer.valueOf(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Return a single parameter value.
     */
    protected static String getSingleParam(List<String> list) {
        if (list != null && list.size() == 1) {
            return list.get(0);
        }
        return null;
    }

    protected static void applyOrderBy(MultivaluedMap<String, String> queryParams, Query query) {
        String orderByClause = getSingleParam(queryParams.get(EbeanModelWriter.ORDER_BY_PARAM_NAME));
        if (StringUtils.isNotBlank(orderByClause)) {
            query.order(orderByClause);
        }
    }

    protected static void applyPageList(MultivaluedMap<String, String> queryParams, Query query) {

        Integer maxRows = getSingleIntegerParam(queryParams.get(EbeanModelWriter.MAX_ROWS_PARAM_NAME));
        if (maxRows != null) {
            query.setMaxRows(maxRows);
        }

        Integer firstRow = getSingleIntegerParam(queryParams.get(EbeanModelWriter.FIRST_ROW_PARAM_NAME));
        if (firstRow != null) {
            query.setFirstRow(firstRow);
        }
    }

    protected static void applyWhere(MultivaluedMap<String, String> queryParams, Query query) {
        List<String> wheres = queryParams.get(EbeanModelWriter.WHERE_PARAM_NAME);
        if (wheres != null)
            for (String w : wheres) {
                query.where(w);
            }
    }

    protected static void applyUriQuery(MultivaluedMap<String, String> queryParams, Query query) {
        applyPathProperties(queryParams, query);
        applyOrderBy(queryParams, query);
        applyPageList(queryParams, query);
        applyWhere(queryParams, query);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
        MessageBodyWriter writer = workers.get().getMessageBodyWriter(type, genericType, annotations, mediaType);
        if (o == null) {
            writer.writeTo(null, type, genericType, annotations, mediaType, httpHeaders, entityStream);
            return;
        }

        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        Query query = null;
        if (o instanceof Finder) {
            query = ((Finder) o).query();
        } else if (o instanceof Query) {
            query = (Query) o;
        } else if (o instanceof ExpressionList) {
            query = ((ExpressionList) o).query();
        } else if (o instanceof FutureList) {
            query = ((FutureList) o).getQuery();
        }
        applyUriQuery(queryParams, query);

        List list = query.findList();

        Class modelType = list.getClass();

        genericType = Object.class;
        if (!list.isEmpty()) {
            genericType = list.get(0).getClass();
        }

        writer.writeTo(list, modelType, genericType, annotations, mediaType, httpHeaders, entityStream);
    }
}