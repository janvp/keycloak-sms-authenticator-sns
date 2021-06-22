package org.keycloak.sms.impl;

import org.apache.commons.lang.RandomStringUtils;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.sms.KeycloakSmsConstants;
import org.keycloak.sms.KeycloakSmsSenderService;
import org.keycloak.sms.impl.gateway.Gateways;
import org.keycloak.sms.impl.gateway.SMSService;
import org.keycloak.sms.impl.gateway.aws.snsclient.SnsNotificationService;
import org.keycloak.sms.impl.gateway.isendpro.ISendProdSMSService;
import org.keycloak.sms.impl.gateway.simulation.SmsSimulationService;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.credential.CredentialModel;
import org.keycloak.models.*;
import org.keycloak.sms.impl.gateway.lyrasms.LyraSMSService;
import org.keycloak.theme.Theme;

import javax.ws.rs.core.MultivaluedMap;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mobile Number Internal management
 * Created by nickpack on 15/08/2017.
 */
public class KeycloakSmsSenderServiceImpl implements KeycloakSmsSenderService {

    private static Logger logger = Logger.getLogger(KeycloakSmsSenderServiceImpl.class);

    private Config.Scope config;

    @Override
    public void close() {

    }

    public void setConfig(Config.Scope config) {
        this.config = config;
    }

    public boolean sendSmsCode(String mobileNumber, AuthenticationFlowContext context) {

        int nrOfDigits = KeycloakSmsUtil.getConfigInt(context.getAuthenticatorConfig(), KeycloakSmsConstants.CONF_PRP_SMS_CODE_LENGTH, 4);
        logger.debug("Using nrOfDigits " + nrOfDigits);

        long ttl = KeycloakSmsUtil.getConfigLong(context.getAuthenticatorConfig(), KeycloakSmsConstants.CONF_PRP_SMS_CODE_TTL, 10 * 60L); // 10 minutes in s
        logger.debug("Using ttl " + ttl + " (s)");

        boolean isAlphaNumeric = KeycloakSmsUtil.getConfigBoolean(context.getAuthenticatorConfig(), KeycloakSmsConstants.CONF_PRP_SMS_CODE_ALPHANUMERIC, true); // 10 minutes in s
        logger.debug("Using alphanumeric " + isAlphaNumeric);

        String code = getSmsCode(nrOfDigits, isAlphaNumeric);
        storeSMSCode(context.getSession().userCredentialManager(), context.getRealm(), context.getUser(), code, new Date().getTime() + (ttl * 1000));

        Theme theme = null;
        Locale locale = context.getSession().getContext().resolveLocale(context.getUser());
        try {
            theme = context.getSession().theme().getTheme(context.getRealm().getLoginTheme(), Theme.Type.LOGIN);
        } catch (Exception e) {
            logger.error("Unable to get theme required to send SMS", e);
        }

        return sendSmsCode(mobileNumber, code, theme, locale);
    }

    public boolean sendSmsCode(String mobileNumber, RequiredActionContext context) {
        int nrOfDigits = config.getInt(KeycloakSmsConstants.CONF_PRP_SMS_CODE_LENGTH, 4);
        logger.debug("Using nrOfDigits " + nrOfDigits);

        long ttl = config.getLong(KeycloakSmsConstants.CONF_PRP_SMS_CODE_TTL, 10 * 60L); // 10 minutes in s
        logger.debug("Using ttl " + ttl + " (s)");

        boolean isAlphaNumeric = Boolean.getBoolean(config.get(KeycloakSmsConstants.CONF_PRP_SMS_CODE_ALPHANUMERIC));
        logger.debug("Using alphanumeric " + isAlphaNumeric);

        String code = getSmsCode(nrOfDigits, isAlphaNumeric);
        storeSMSCode(context.getSession().userCredentialManager(), context.getRealm(), context.getUser(), code, new Date().getTime() + (ttl * 1000));

        Theme theme = null;
        Locale locale = context.getSession().getContext().resolveLocale(context.getUser());
        try {
            theme = context.getSession().theme().getTheme(context.getRealm().getLoginTheme(), Theme.Type.LOGIN);
        } catch (Exception e) {
            logger.error("Unable to get theme required to send SMS", e);
        }

        return sendSmsCode(mobileNumber, code, theme, locale);
    }

    private String getSmsCode(int length, boolean isAlphaNumeric) {
        if (length < 1) {
            throw new RuntimeException("Number of digits must be bigger than 0");
        }
        return isAlphaNumeric ? RandomStringUtils.randomAlphanumeric(length) : RandomStringUtils.randomNumeric(length);
    }

    private boolean sendSmsCode(String mobileNumber, String code, Theme theme, Locale locale) {
        // Send an SMS
        String smsUsr = config.get(KeycloakSmsConstants.CONF_PRP_SMS_CLIENTTOKEN);
        String smsPwd = config.get(KeycloakSmsConstants.CONF_PRP_SMS_CLIENTSECRET);
        String smsFrom = config.get(KeycloakSmsConstants.CONF_PRP_SMS_FROM);
        String gateway = config.get(KeycloakSmsConstants.CONF_PRP_SMS_GATEWAY);
        String endpoint = config.get(KeycloakSmsConstants.CONF_PRP_SMS_GATEWAY_ENDPOINT);
        boolean isProxy = Boolean.getBoolean(config.get(KeycloakSmsConstants.PROXY_ENABLED));

        String template = KeycloakSmsUtil.getMessage(theme, locale, KeycloakSmsConstants.CONF_PRP_SMS_TEXT);
        String smsText = KeycloakSmsUtil.createMessage(template, code, mobileNumber);

        logger.debug("Sending " + code + "  to mobileNumber " + mobileNumber);
        logger.debug("Using config : "+gateway+" - "+smsPwd+" - " +endpoint);

        boolean result;
        SMSService smsService;
        try {
            Gateways g = Gateways.valueOf(gateway);
            switch(g) {
                case LYRA_SMS:
                    smsService = new LyraSMSService(endpoint,isProxy);
                    break;
                case ISENDPRO_SMS:
                    smsService = new ISendProdSMSService(endpoint, isProxy, smsFrom);
                    break;
                case AMAZON_SNS:
                    smsService = new SnsNotificationService();
                    break;
                case SIMULATION:
                    smsService = new SmsSimulationService();
                    break;
                default:
                    smsService = new SnsNotificationService();
            }

            result = smsService.send(
                    KeycloakSmsUtil.checkAndFormatMobileNumber(mobileNumber),
                    smsText,
                    smsUsr,
                    smsPwd
            );
            return result;
        } catch(Exception e) {
            logger.warn("Fail to send SMS : " + e.getLocalizedMessage());
            return false;
        }
    }

    private void storeSMSCode(UserCredentialManager credentialManager, RealmModel realm, UserModel user, String code, Long expiringAt) {
        UserCredentialModel credentials = new UserCredentialModel();
        credentials.setType(KeycloakSmsConstants.USR_CRED_MDL_SMS_CODE);
        credentials.setValue(code);
        credentialManager.updateCredential(realm, user, credentials);
        credentials.setType(KeycloakSmsConstants.USR_CRED_MDL_SMS_EXP_TIME);
        credentials.setValue((expiringAt).toString());
        credentialManager.updateCredential(realm, user, credentials);
    }

    public CODE_STATUS validateCode(RequiredActionContext context) {
        logger.debug("validateCode called ... ");
        return validateCode(context.getSession().userCredentialManager(), context.getRealm(), context.getUser(), context.getHttpRequest());
    }

    @Override
    public CODE_STATUS validateCode(AuthenticationFlowContext context) {
        logger.debug("validateCode called ... ");
        return validateCode(context.getSession().userCredentialManager(), context.getRealm(), context.getUser(), context.getHttpRequest());
    }

    @Override
    public void updateVerifiedMobilenumber(UserModel user) {

        boolean onlyForVerification=config.getBoolean(KeycloakSmsConstants.MOBILE_VERIFICATION_ENABLED);

        if(onlyForVerification){
            //Only verification mode
            List<String> mobileNumberCreds = user.getAttribute(KeycloakSmsConstants.ATTR_MOBILE);
            if (mobileNumberCreds != null && !mobileNumberCreds.isEmpty()) {
                user.setAttribute(KeycloakSmsConstants.ATTR_MOBILE_VERIFIED,mobileNumberCreds);
            }
        }
    }

    private CODE_STATUS validateCode(UserCredentialManager credentialManager, RealmModel realm, UserModel user, HttpRequest request) {
        MultivaluedMap<String, String> formData = request.getDecodedFormParameters();
        String enteredCode = formData.getFirst(KeycloakSmsConstants.ANSW_SMS_CODE);
        CODE_STATUS result = CODE_STATUS.INVALID;
        List codeCreds = credentialManager.getStoredCredentialsByType(realm, user, KeycloakSmsConstants.USR_CRED_MDL_SMS_CODE);
        /*List timeCreds = session.userCredentialManager().getStoredCredentialsByType(context.getRealm(), context.getUser(), KeycloakSmsAuthenticatorConstants.USR_CRED_MDL_SMS_EXP_TIME);*/

        CredentialModel expectedCode = (CredentialModel) codeCreds.get(0);
        /*CredentialModel expTimeString = (CredentialModel) timeCreds.get(0);*/

        logger.debug("Expected code = " + expectedCode.getValue() + "    entered code = " + enteredCode);

        if (expectedCode != null) {
            result = enteredCode.equals(expectedCode.getValue()) ? CODE_STATUS.VALID : CODE_STATUS.INVALID;
            /*long now = new Date().getTime();

            logger.debug("Valid code expires in " + (Long.parseLong(expTimeString.getValue()) - now) + " ms");
            if (result == CODE_STATUS.VALID) {
                if (Long.parseLong(expTimeString.getValue()) < now) {
                    logger.debug("Code is expired !!");
                    result = CODE_STATUS.EXPIRED;
                }
            }*/
        }
        logger.debug("result : " + result);
        return result;
    }

    public boolean checkNumberAlreadyTaken(String mobileNumber, KeycloakSession session, UserModel user, RealmModel realm) {
        boolean isCheckNumberAlreadyTaken = config.getBoolean(KeycloakSmsConstants.CHECK_NUMBER_ALREADY_EXISTS);
        logger.debug("checking number exists ? "+isCheckNumberAlreadyTaken);
        boolean numberAlreadyTaken = false;
        if (isCheckNumberAlreadyTaken) {
            if (mobileNumber != null) {
                logger.debug("search for user with phone " + mobileNumber);
                List<UserModel> users = session
                        .users()
                        .searchForUserByUserAttribute("mobile_number", mobileNumber, realm);

                logger.debug("search size ? " + users.size());
                numberAlreadyTaken = users.size() > 1 || (!(users.isEmpty() || (users.size() == 1 && users.get(0).equals(user))));
            }
            logger.debug("Number already taken ? " + numberAlreadyTaken);
        }
        return numberAlreadyTaken;
    }
}
