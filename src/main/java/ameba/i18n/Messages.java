package ameba.i18n;

import ameba.core.Requests;
import ameba.util.ClassUtils;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * @author icode
 */
public class Messages {

    public static final String DEFAULT_BUNDLE_NAME = "conf/messages/message";
    private static final Table<String, Locale, ResourceBundle> RESOURCE_BUNDLES = HashBasedTable.create();


    private Messages() {
    }

    public static String get(String key, Object... args) {
        return get(DEFAULT_BUNDLE_NAME, key, args);
    }


    public static String get(String bundleName, String key, Object... args) {
        return get(bundleName, getLocale(), key, args);
    }

    private static Locale getLocale() {
        Locale locale = null;
        try {
            List<Locale> acceptableLanguages = Requests.getAcceptableLanguages();
            if (acceptableLanguages != null && acceptableLanguages.size() > 0) {
                locale = acceptableLanguages.get(0);
            }
        } catch (Exception e) {
            // no op
        }
        if (locale == null) {
            locale = Locale.getDefault();
        }
        return locale;
    }

    public static String get(String bundleName, Locale locale, String key, Object... args) {
        try {
            ResourceBundle bundle = RESOURCE_BUNDLES.get(bundleName, locale);

            if (bundle == null) {
                try {
                    bundle = ResourceBundle.getBundle(bundleName,
                            locale,
                            ClassUtils.getContextClassLoader(),
                            new MultiResourceBundleControl());
                } catch (MissingResourceException e) {
                    // no op
                }

                if (bundle == null) {
                    return getDefaultMessage(key, args);
                } else {
                    RESOURCE_BUNDLES.put(bundleName, locale, bundle);
                }
            }

            if (key == null) {
                key = "undefined";
            }

            String msg;
            try {
                msg = bundle.getString(key);
            } catch (MissingResourceException e) {
                // notice that this may throw a MissingResourceException of its own (caught below)
                msg = bundle.getString("undefined");
            }

            return MessageFormat.format(msg, args);

        } catch (MissingResourceException e) {
            return getDefaultMessage(key, args);
        }

    }

    private static String getDefaultMessage(String key, Object... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("[failed to localize] ");
        sb.append(key);
        if (args != null) {
            sb.append('(');
            for (int i = 0; i < args.length; ++i) {
                if (i != 0) {
                    sb.append(", ");
                }
                sb.append(String.valueOf(args[i]));
            }
            sb.append(')');
        }
        return sb.toString();
    }
}
