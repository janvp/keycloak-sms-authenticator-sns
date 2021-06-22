package org.keycloak.authenticator;

import org.keycloak.action.required.KeycloakSmsMobilenumberValidationRequiredAction;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.services.Urls;
import org.keycloak.services.resources.LoginActionsService;
import org.keycloak.sms.KeycloakSmsConstants;
import org.keycloak.sms.KeycloakSmsSenderService;
import org.keycloak.sms.impl.KeycloakSmsUtil;
import org.jboss.logging.Logger;
import org.keycloak.action.required.KeycloakSmsMobilenumberRequiredAction;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.*;
import org.keycloak.services.resources.RealmsResource;

import javax.ws.rs.core.Response;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;

/**
 * Created by joris on 11/11/2016.
 */
public class KeycloakSmsAuthenticator implements Authenticator {

    private static Logger logger = Logger.getLogger(KeycloakSmsAuthenticator.class);

    private boolean isOnlyForVerificationMode(boolean onlyForVerification,String mobileNumber,String mobileNumberVerified){
        return (mobileNumber ==null || onlyForVerification==true && !mobileNumber.equals(mobileNumberVerified) );
    }

    private String getMobileNumber(UserModel user){
        List<String> mobileNumberCreds = user.getAttribute(KeycloakSmsConstants.ATTR_MOBILE);

        String mobileNumber = null;
        if (mobileNumberCreds != null && !mobileNumberCreds.isEmpty()) {
            mobileNumber = mobileNumberCreds.get(0);
        }

        return  mobileNumber;
    }

    private String getMobileNumberVerified(UserModel user){
        List<String> mobileNumberVerifieds = user.getAttribute(KeycloakSmsConstants.ATTR_MOBILE_VERIFIED);

        String mobileNumberVerified = null;
        if (mobileNumberVerifieds != null && !mobileNumberVerifieds.isEmpty()) {
            mobileNumberVerified = mobileNumberVerifieds.get(0);
        }
        return  mobileNumberVerified;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        logger.debug("authenticate called ... context = " + context);
        UserModel user = context.getUser();
        AuthenticatorConfigModel config = context.getAuthenticatorConfig();

        boolean onlyForVerification= KeycloakSmsUtil.getConfigBoolean(config, KeycloakSmsConstants.MOBILE_VERIFICATION_ENABLED);

        String mobileNumber =getMobileNumber(user);
        String mobileNumberVerified = getMobileNumberVerified(user);

        if (onlyForVerification==false || isOnlyForVerificationMode(onlyForVerification, mobileNumber, mobileNumberVerified)){
            if (mobileNumber != null) {
                // The mobile number is configured --> send an SMS
                KeycloakSmsSenderService provider = context.getSession().getProvider(KeycloakSmsSenderService.class);

                if (provider.sendSmsCode(mobileNumber, context)) {
                    Response challenge = context.form()
                            .setAttribute("mobile_number", mobileNumber)
                            .createForm("sms-validation.ftl");
                    context.challenge(challenge);
                } else {
                    Response challenge = context.form()
                            .setError("sms-auth.not.send")
                            .createForm("sms-validation-error.ftl");
                    context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, challenge);
                }
            } else {
                boolean isAskingFor=KeycloakSmsUtil.getConfigBoolean(config, KeycloakSmsConstants.MOBILE_ASKFOR_ENABLED);
                if (isAskingFor) {
                    //Enable access and ask for mobilenumber
                    logger.debug("Adding required action to get mobile number");
                    user.addRequiredAction(KeycloakSmsMobilenumberRequiredAction.PROVIDER_ID);
                    context.success();
                } else {
                    // The mobile number is NOT configured --> complain
                    Response challenge = context.form()
                            .setError("sms-auth.not.mobile")
                            .createForm("sms-validation-error.ftl");
                    context.failureChallenge(AuthenticationFlowError.CLIENT_CREDENTIALS_SETUP_REQUIRED, challenge);
                }
            }
        } else {
            logger.debug("Skip SMS code because onlyForVerification " + onlyForVerification + " or  mobileNumber==mobileNumberVerified");
            context.success();
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        logger.debug("action called ... context = " + context);

        boolean changeNumber = Boolean.valueOf(context.getHttpRequest().getFormParameters().getFirst("changeNumber"));

        logger.info("Change Number from validation action ? "+changeNumber);
        if (changeNumber) {
            context.getUser().removeAttribute("mobile_number");
            context.getUser().removeAttribute("mobile_number_verified");
            context.getUser().removeRequiredAction(KeycloakSmsMobilenumberValidationRequiredAction.PROVIDER_ID);
            context.getUser().addRequiredAction(KeycloakSmsMobilenumberRequiredAction.PROVIDER_ID);
            context.success();
        } else {
            KeycloakSmsSenderService provider = context.getSession().getProvider(KeycloakSmsSenderService.class);
            KeycloakSmsSenderService.CODE_STATUS status = provider.validateCode(context);
            Response challenge = null;
            switch (status) {
                case EXPIRED:
                    challenge = context.form()
                            .setError("sms-auth.code.expired")
                            .createForm("sms-validation.ftl");
                    context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE, challenge);
                    break;

                case INVALID:
                    if (context.getExecution().getRequirement() == AuthenticationExecutionModel.Requirement.ALTERNATIVE) {
                        logger.debug("Calling context.attempted()");
                        context.attempted();
                    } else if (context.getExecution().getRequirement() == AuthenticationExecutionModel.Requirement.REQUIRED) {
                        challenge = context.form()
                                .setError("sms-auth.code.invalid")
                                .createForm("sms-validation.ftl");
                        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
                    } else {
                        // Something strange happened
                        logger.warn("Undefined execution ...");
                    }
                    break;

                case VALID:
                    context.success();
                    provider.updateVerifiedMobilenumber(context.getUser());
                    break;

            }
        }
    }

    @Override
    public boolean requiresUser() {
        logger.debug("requiresUser called ... returning true");
        return true;
    }
    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        logger.debug("configuredFor called ... session=" + session + ", realm=" + realm + ", user=" + user);
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        logger.debug("setRequiredActions called ... session=" + session + ", realm=" + realm + ", user=" + user);
    }
    @Override
    public void close() {
        logger.debug("close called ...");
    }

}
