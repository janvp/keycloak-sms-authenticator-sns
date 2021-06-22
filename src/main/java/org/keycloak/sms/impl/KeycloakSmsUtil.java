package org.keycloak.sms.impl;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.jboss.logging.Logger;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.theme.Theme;

import java.io.IOException;
import java.util.Locale;

/**
 * Created by joris on 18/11/2016.
 */
public class KeycloakSmsUtil {

    private static Logger logger = Logger.getLogger(KeycloakSmsUtil.class);

    public static Long getConfigLong(AuthenticatorConfigModel config, String configName, Long defaultValue) {

        Long value = defaultValue;

        if (config.getConfig() != null) {
            // Get value
            Object obj = config.getConfig().get(configName);
            try {
                value = Long.valueOf((String) obj);
            } catch (NumberFormatException nfe) {
                logger.error("Can not convert " + obj + " to a number.");
            }
        }
        return value;
    }

    public static int getConfigInt(AuthenticatorConfigModel config, String configName, int defaultValue) {

        int value = defaultValue;

        if (config.getConfig() != null) {
            // Get value
            Object obj = config.getConfig().get(configName);
            try {
                value = Integer.valueOf((String) obj);
            } catch (NumberFormatException nfe) {
                logger.error("Can not convert " + obj + " to a number.");
            }
        }
        return value;
    }

    public static Boolean getConfigBoolean(AuthenticatorConfigModel config, String configName) {
        return getConfigBoolean(config, configName, true);
    }

    public static Boolean getConfigBoolean(AuthenticatorConfigModel config, String configName, Boolean defaultValue) {

        Boolean value = defaultValue;

        if (config.getConfig() != null) {
            // Get value
            Object obj = config.getConfig().get(configName);
            try {
                value = Boolean.valueOf((String) obj);
            } catch (NumberFormatException nfe) {
                logger.error("Can not convert " + obj + " to a boolean.");
            }
        }
        return value;
    }

    /**
     * Check mobile number normative strcuture
     * @param mobileNumber
     * @return formatted mobile number
     */
    public static String checkAndFormatMobileNumber(String mobileNumber) throws Exception {

        PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
        Phonenumber.PhoneNumber phone = phoneUtil.parse(mobileNumber, "BE");

        if (!phoneUtil.getNumberType(phone).equals(PhoneNumberUtil.PhoneNumberType.MOBILE)) {
            logger.error("Invalid mobile phone number (not a mobile)");
            throw new Exception("Phone number is not a mobile number");
        }

        String formatted = phoneUtil.format(phone, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL);

        logger.debug("formatted number "+formatted+" from "+mobileNumber);
        return formatted;
    }

    public static String getMessage(Theme theme, Locale locale, String key){
        String result=null;
        try {
            result = theme.getMessages(locale).getProperty(key);
        }catch (IOException e){
            logger.warn(key + "not found in messages");
        }
        return result;
    }

    public static String createMessage(String text,String code, String mobileNumber) {
        if(text !=null){
            text = text.replaceAll("%sms-code%", code);
            text = text.replaceAll("%phonenumber%", mobileNumber);
        }
        return text;
    }
}
