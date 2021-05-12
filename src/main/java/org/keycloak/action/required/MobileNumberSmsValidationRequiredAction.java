package org.keycloak.action.required;

import org.jboss.logging.Logger;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.UserModel;
import org.keycloak.service.SmsSenderService;
import org.keycloak.service.SmsSenderServiceImpl;
import org.keycloak.util.UserProfile;

import javax.ws.rs.core.Response;

/**
 * Action to validate user mobile number by sms
 */
public class MobileNumberSmsValidationRequiredAction implements RequiredActionProvider {
    private static Logger logger = Logger.getLogger(MobileNumberSmsValidationRequiredAction.class);
    public static final String PROVIDER_ID = "sms_auth_check_mobile_validation";

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        logger.debug("requiredActionChallenge for Mobile Number Verification required action called ...");

        UserModel user = context.getUser();

        var mobileNumber = UserProfile.getMobileNumber(user, false);
        var verifiedMobileNumber = UserProfile.getMobileNumber(user, true);

        if (mobileNumber.isPresent() && verifiedMobileNumber.isPresent() && mobileNumber.get().equalsIgnoreCase(verifiedMobileNumber.get())) {
            // Mobile number is configured and verified
            context.ignore();
        } else if (mobileNumber.isPresent()) {
            logger.debug("SMS validation required ...");

            SmsSenderService provider = context.getSession().getProvider(SmsSenderService.class);
            if (provider.sendSmsCode(mobileNumber.get(), context, false)) {
                Response challenge = context.form()
                        .setAttribute("mobile_number", mobileNumber.get())
                        .setAttribute("code_digits", provider.getCodeDigits(context.getSession(), context.getUser()))
                        .createForm("sms-validation.ftl");

                context.challenge(challenge);
            } else {
                logger.warn("Fail to send SMS to " + mobileNumber.get() + ", removing number from profile");
                UserProfile.removeMobileNumberAndUpdateActions(user);

                Response challenge = context.form()
                        .setError("sms-auth.not.send", mobileNumber.get())
                        .createForm("sms-validation-error.ftl");
                context.challenge(challenge);
            }
        }
    }

    @Override
    public void processAction(RequiredActionContext context) {
        var user = context.getUser();

        logger.debug("action called ... context = " + context);

        boolean changeNumber = Boolean.valueOf(context.getHttpRequest().getFormParameters().getFirst("changeNumber"));
        boolean sendAgain = Boolean.valueOf(context.getHttpRequest().getFormParameters().getFirst("sendAgain"));
        logger.debug("Change Number from validation action ? " + changeNumber);

        if (changeNumber) {
            UserProfile.removeMobileNumberAndUpdateActions(user);
            context.success();
        } else if (context.getHttpRequest().getDecodedFormParameters().getFirst(SmsSenderServiceImpl.SMS_CODE_FORM_FIELD) != null){
            SmsSenderService provider = context.getSession().getProvider(SmsSenderService.class);
            SmsSenderService.CODE_STATUS status = provider.validateCode(context);
            Response challenge;

            var mobileNumber = UserProfile.getMobileNumber(user, false);
            switch (status) {
                case EXPIRED:
                    provider.sendSmsCode(mobileNumber.get(), context, sendAgain);
                    challenge = context.form()
                            .setAttribute("mobile_number", mobileNumber.orElse(null))
                            .setAttribute("code_digits", provider.getCodeDigits(context.getSession(), context.getUser()))
                            .setError("sms-auth.code.expired")
                            .createForm("sms-validation.ftl");
                    context.challenge(challenge);
                    break;

                case INVALID:
                    if (sendAgain) {
                        provider.sendSmsCode(mobileNumber.get(), context, true);
                        challenge = context.form()
                                .setAttribute("mobile_number", mobileNumber.orElse(null))
                                .setAttribute("code_digits", provider.getCodeDigits(context.getSession(), user))
                                .createForm("sms-validation.ftl");
                    } else {
                        challenge = context.form()
                                .setAttribute("mobile_number", mobileNumber.orElse(null))
                                .setAttribute("code_digits", provider.getCodeDigits(context.getSession(), context.getUser()))
                                .setError("sms-auth.code.invalid")
                                .createForm("sms-validation.ftl");
                    }
                    context.challenge(challenge);
                    break;

                case VALID:
                    logger.info("Mobile number successfully verified !");
                    UserProfile.addVerifiedNumberAndUpdateActions(user, mobileNumber.get());
                    context.success();
                    break;
            }
        } else {
           context.success();
        }
    }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        logger.debug("evaluateTriggers called ...");
    }

    @Override
    public void close() {
        logger.debug("close called ...");
    }
}
