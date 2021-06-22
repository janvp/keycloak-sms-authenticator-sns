package org.keycloak.action.required;

import org.jboss.logging.Logger;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.UserModel;
import org.keycloak.sms.KeycloakSmsConstants;
import org.keycloak.sms.KeycloakSmsSenderService;
import org.keycloak.sms.impl.KeycloakSmsUtil;

import javax.ws.rs.core.Response;
import java.util.List;


/**
 * Created by nickpack on 15/08/2017.
 */
public class KeycloakSmsMobilenumberValidationRequiredAction implements RequiredActionProvider {
    private static Logger logger = Logger.getLogger(KeycloakSmsMobilenumberValidationRequiredAction.class);
    public static final String PROVIDER_ID = "sms_auth_check_mobile_validation";

    public void evaluateTriggers(RequiredActionContext context) {
        logger.debug("evaluateTriggers called ...");
    }

    public void requiredActionChallenge(RequiredActionContext context) {
        logger.debug("requiredActionChallenge for Mobile Number Verification required action called ...");

        UserModel user = context.getUser();

        List<String> mobileNumberCreds = user.getAttribute(KeycloakSmsConstants.ATTR_MOBILE);
        List<String> mobileNumberVerifiedCreds = user.getAttribute(KeycloakSmsConstants.ATTR_MOBILE_VERIFIED);

        String mobileNumber = null;
        String mobileNumberValidation = null;

        try {
            if (mobileNumberCreds != null && !mobileNumberCreds.isEmpty() && mobileNumberCreds.get(0) != null) {
                mobileNumber = KeycloakSmsUtil.checkAndFormatMobileNumber(mobileNumberCreds.get(0));
            }
            if (mobileNumberVerifiedCreds != null && !mobileNumberVerifiedCreds.isEmpty() && mobileNumberVerifiedCreds.get(0) != null) {
                mobileNumberValidation = KeycloakSmsUtil.checkAndFormatMobileNumber(mobileNumberVerifiedCreds.get(0));
            }
        } catch (Exception e) {
            logger.warn("Invalid phone number "+e.getLocalizedMessage());
        }

        if (mobileNumber != null && mobileNumberValidation != null && mobileNumber.equalsIgnoreCase(mobileNumberValidation)) {
            // Mobile number is configured and validated
            context.ignore();
        } else if (mobileNumberValidation == null) {
            logger.debug("SMS validation required ...");

            KeycloakSmsSenderService provider = context.getSession().getProvider(KeycloakSmsSenderService.class);
            if (provider.sendSmsCode(mobileNumber, context)) {
                Response challenge = context.form()
                        .setAttribute("mobile_number", user.getAttributes().get("mobile_number").get(0))
                        .createForm("sms-validation.ftl");

                context.challenge(challenge);
            } else {
                String attemptNumber = user.getAttributes().get("mobile_number").get(0);
                logger.warn("Fail to send SMS to " + attemptNumber + ", removing number from profile");
                context.getUser().removeAttribute("mobile_number");
                context.getUser().removeRequiredAction(KeycloakSmsMobilenumberValidationRequiredAction.PROVIDER_ID);
                context.getUser().addRequiredAction(KeycloakSmsMobilenumberRequiredAction.PROVIDER_ID);

                Response challenge = context.form()
                        .setError("sms-auth.not.send", attemptNumber)
                        .createForm("sms-validation-error.ftl");
                context.challenge(challenge);
            }
        }
    }

    public void processAction(RequiredActionContext context) {
        logger.debug("action called ... context = " + context);

        boolean changeNumber = Boolean.valueOf(context.getHttpRequest().getFormParameters().getFirst("changeNumber"));
        logger.debug("Change Number from validation action ? "+changeNumber);

        if (changeNumber) {
            context.getUser().removeAttribute("mobile_number");
            context.getUser().removeAttribute("mobile_number_verified");
            context.getUser().removeRequiredAction(KeycloakSmsMobilenumberValidationRequiredAction.PROVIDER_ID);
            context.getUser().addRequiredAction(KeycloakSmsMobilenumberRequiredAction.PROVIDER_ID);
            context.success();
        } else if (context.getHttpRequest().getDecodedFormParameters().getFirst(KeycloakSmsConstants.ANSW_SMS_CODE) != null){
            KeycloakSmsSenderService provider = context.getSession().getProvider(KeycloakSmsSenderService.class);
            KeycloakSmsSenderService.CODE_STATUS status = provider.validateCode(context);
            Response challenge;

            switch (status) {
                case EXPIRED:
                    challenge = context.form()
                            .setError("sms-auth.code.expired")
                            .createForm("sms-validation.ftl");
                    context.challenge(challenge);
                    break;

                case INVALID:
                    challenge = context.form()
                            .setError("sms-auth.code.invalid")
                            .createForm("sms-validation.ftl");
                    context.challenge(challenge);
                    break;

                case VALID:
                    logger.info("Mobile number successfully verified !");
                    context.getUser().removeRequiredAction(KeycloakSmsMobilenumberRequiredAction.PROVIDER_ID);
                    context.success();
                    provider.updateVerifiedMobilenumber(context.getUser());
                    break;
            }
        } else {
           context.success();
        }
    }

    public void close() {
        logger.debug("close called ...");
    }
}
