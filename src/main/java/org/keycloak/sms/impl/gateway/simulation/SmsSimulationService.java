package org.keycloak.sms.impl.gateway.simulation;

import org.jboss.logging.Logger;
import org.keycloak.sms.impl.gateway.SMSService;

public class SmsSimulationService implements SMSService {

    private static Logger logger = Logger.getLogger(SmsSimulationService.class);

    public boolean send(String phoneNumber, String message, String clientToken, String clientSecret) {
        logger.info(message);

        return true;
    }
}
