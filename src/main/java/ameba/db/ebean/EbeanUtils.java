package ameba.db.ebean;

import ameba.core.Application;
import ameba.core.Requests;
import ameba.db.ebean.filter.Filter;
import ameba.db.ebean.internal.ListExpressionValidation;
import ameba.db.ebean.jackson.CommonBeanSerializer;
import ameba.exception.UnprocessableEntityException;
import ameba.i18n.Messages;
import ameba.message.filtering.EntityFieldsUtils;
import ameba.message.internal.BeanPathProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.ebean.FetchPath;
import io.ebean.OrderBy;
import io.ebean.Query;
import io.ebean.bean.EntityBean;
import io.ebean.bean.EntityBeanIntercept;
import io.ebeaninternal.api.SpiEbeanServer;
import io.ebeaninternal.api.SpiExpression;
import io.ebeaninternal.api.SpiExpressionList;
import io.ebeaninternal.api.SpiQuery;
import io.ebeaninternal.server.deploy.BeanDescriptor;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.internal.inject.InjectionManager;

import javax.ws.rs.container.ResourceInfo;
import java.util.*;

import static io.ebean.OrderBy.Property;

/**
 * <p>EbeanUtils class.</p>
 *
 * @author icode
 * @since 0.1.6e
 */
public class EbeanUtils {
    /**
     * Constant <code>PATH_PROPS_PARSED="EbeanUtils.class + .BeanPathProperties"</code>
     */
    public static final String PATH_PROPS_PARSED = EbeanUtils.class + ".BeanPathProperties";


    private EbeanUtils() {
    }

    /**
     * <p>forceUpdateAllProperties.</p>
     *
     * @param server a {@link io.ebeaninternal.api.SpiEbeanServer} object.
     * @param model  a T object.
     * @param <T>    a T object.
     */
    @SuppressWarnings("unchecked")
    public static <T> void forceUpdateAllProperties(SpiEbeanServer server, T model) {
        forceUpdateAllProperties(server.getBeanDescriptor((Class<T>) model.getClass()), model);
    }

    /**
     * <p>forceUpdateAllProperties.</p>
     *
     * @param beanDescriptor a {@link io.ebeaninternal.server.deploy.BeanDescriptor} object.
     * @param model          a T object.
     * @param <T>            a T object.
     */
    public static <T> void forceUpdateAllProperties(BeanDescriptor<T> beanDescriptor, T model) {
        EntityBeanIntercept intercept = ((EntityBean) model)._ebean_getIntercept();
        intercept.setLoaded();
        int idIndex = beanDescriptor.getIdProperty().getPropertyIndex();
        for (int i = 0; i < intercept.getPropertyLength(); i++) {
            if (i != idIndex) {
                intercept.markPropertyAsChanged(i);
                intercept.setLoadedProperty(i);
            }
        }
    }

    /**
     * parse uri query param to BeanPathProperties for Ebean.json().toJson()
     *
     * @return BeanPathProperties
     * @see CommonBeanSerializer#serialize(Object, JsonGenerator, SerializerProvider)
     */
    public static FetchPath getRequestFetchPath() {
        Object properties = Requests.getProperty(PATH_PROPS_PARSED);
        if (properties == null) {
            BeanPathProperties pathProperties = EntityFieldsUtils.parsePathProperties();
            if (pathProperties == null) {
                Requests.setProperty(PATH_PROPS_PARSED, false);
            } else {
                properties = EbeanPathProps.of(pathProperties);
                Requests.setProperty(PATH_PROPS_PARSED, properties);
            }
        } else if (properties.equals(false)) {
            return null;
        }
        return (FetchPath) properties;
    }

    /**
     * <p>appendOrder.</p>
     *
     * @param orderBy       a {@link io.ebean.OrderBy} object.
     * @param orderByClause a {@link java.lang.String} object.
     * @param <T>           a T object.
     */
    public static <T> void appendOrder(OrderBy<T> orderBy, String orderByClause) {

        if (orderByClause == null) {
            return;
        }

        String[] chunks = orderByClause.split(",");
        for (String chunk : chunks) {
            String[] pairs = chunk.split(" ");
            Property p = parseOrderProperty(pairs);
            if (p != null) {
                orderBy.add(p);
            }
        }
    }

    /**
     * <p>checkQuery.</p>
     *
     * @param query   a {@link io.ebean.Query} object.
     * @param manager a {@link InjectionManager} object.
     */
    public static void checkQuery(Query<?> query, InjectionManager manager) {
        checkQuery(query, null, null, manager);
    }

    /**
     * <p>checkQuery.</p>
     *
     * @param query     a {@link io.ebean.Query} object.
     * @param whitelist a {@link java.util.Set} object.
     * @param blacklist a {@link java.util.Set} object.
     * @param manager   a {@link InjectionManager} object.
     */
    public static void checkQuery(Query<?> query, Set<String> whitelist,
                                  Set<String> blacklist, InjectionManager manager) {
        ResourceInfo resource = manager.getInstance(ResourceInfo.class);
        Class<?> rc = resource.getResourceClass();
        Set<String> wl = null, bl = null;
        if (rc != null) {
            Filter filter = rc.getAnnotation(Filter.class);

            if (filter != null) {
                if (filter.whitelist().length > 0) {
                    wl = Sets.newLinkedHashSet();
                    Collections.addAll(wl, filter.whitelist());
                }
                if (filter.blacklist().length > 0) {
                    bl = Sets.newLinkedHashSet();
                    Collections.addAll(bl, filter.blacklist());
                }
            }
        }

        if (whitelist != null) {
            if (wl == null) {
                wl = Sets.newLinkedHashSet();
            }
            wl.addAll(whitelist);
        }

        if (blacklist != null) {
            if (bl == null) {
                bl = Sets.newLinkedHashSet();
            }
            bl.addAll(blacklist);
        }
        checkQuery((SpiQuery) query, wl, bl, manager.getInstance(Application.Mode.class).isProd());
    }

    /**
     * <p>checkQuery.</p>
     *
     * @param query         a {@link io.ebeaninternal.api.SpiQuery} object.
     * @param whitelist     a {@link java.util.Set} object.
     * @param blacklist     a {@link java.util.Set} object.
     * @param ignoreUnknown a boolean.
     */
    public static void checkQuery(SpiQuery<?> query, Set<String> whitelist,
                                  Set<String> blacklist, boolean ignoreUnknown) {
        checkQuery(
                query,
                new ListExpressionValidation(
                        query.getBeanDescriptor(), whitelist, blacklist
                ),
                ignoreUnknown
        );
    }

    /**
     * <p>checkQuery.</p>
     *
     * @param query         a {@link io.ebeaninternal.api.SpiQuery} object.
     * @param validation    a {@link ameba.db.ebean.internal.ListExpressionValidation} object.
     * @param ignoreUnknown a boolean.
     */
    public static void checkQuery(SpiQuery<?> query, ListExpressionValidation validation, boolean ignoreUnknown) {
        if (query != null) {
            validate(query.getWhereExpressions(), validation, ignoreUnknown);
            validate(query.getHavingExpressions(), validation, ignoreUnknown);
            validate(query.getOrderBy(), validation, ignoreUnknown);

            Set<String> invalid = validation.getUnknownProperties();

            if (!ignoreUnknown && !invalid.isEmpty()) {
                UnprocessableEntityException.throwQuery(invalid);
            }
        }
    }

    /**
     * <p>validate.</p>
     *
     * @param expressions   a {@link io.ebeaninternal.api.SpiExpressionList} object.
     * @param validation    a {@link ameba.db.ebean.internal.ListExpressionValidation} object.
     * @param ignoreUnknown a boolean.
     */
    public static void validate(SpiExpressionList<?> expressions,
                                ListExpressionValidation validation,
                                boolean ignoreUnknown) {
        if (expressions == null) return;
        List<SpiExpression> list = expressions.getUnderlyingList();
        Iterator<SpiExpression> it = list.iterator();
        while (it.hasNext()) {
            it.next().validate(validation);
            if (ignoreUnknown && !validation.lastValid()) {
                it.remove();
            }
        }
    }

    /**
     * <p>validate.</p>
     *
     * @param orderBy       a {@link io.ebean.OrderBy} object.
     * @param validation    a {@link ameba.db.ebean.internal.ListExpressionValidation} object.
     * @param ignoreUnknown a boolean.
     */
    public static void validate(OrderBy<?> orderBy,
                                ListExpressionValidation validation,
                                boolean ignoreUnknown) {
        if (orderBy == null) return;
        Iterator<Property> it = orderBy.getProperties().iterator();
        while (it.hasNext()) {
            validation.validate(it.next().getProperty());
            if (ignoreUnknown && !validation.lastValid()) {
                it.remove();
            }
        }
    }

    private static Property parseOrderProperty(String[] pairs) {
        if (pairs.length == 0) {
            return null;
        }

        ArrayList<String> wordList = Lists.newArrayListWithCapacity(pairs.length);
        for (String pair : pairs) {
            if (StringUtils.isNotBlank(pair)) {
                wordList.add(pair);
            }
        }
        if (wordList.isEmpty()) {
            return null;
        }
        String field = wordList.get(0);
        if (wordList.size() == 1) {
            if (field.startsWith("-")) {
                return new Property(field.substring(1), false);
            } else {
                return new Property(field, true);
            }
        }
        if (wordList.size() == 2) {
            boolean asc = isOrderAscending(wordList.get(1));
            return new Property(field, asc);
        }

        throw new UnprocessableEntityException(
                Messages.get("info.query.orderby1.unprocessable.entity",
                        Arrays.toString(pairs), wordList.size())
        );
    }

    private static boolean isOrderAscending(String s) {
        s = s.toLowerCase();
        if (s.startsWith("asc")) {
            return true;
        }
        if (s.startsWith("desc")) {
            return false;
        }
        throw new UnprocessableEntityException(Messages.get("info.query.orderby0.unprocessable.entity", s));
    }
}
