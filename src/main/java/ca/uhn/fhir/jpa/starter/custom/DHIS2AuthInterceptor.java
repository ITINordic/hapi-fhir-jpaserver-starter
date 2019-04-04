package ca.uhn.fhir.jpa.starter.custom;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import ca.uhn.fhir.rest.server.interceptor.auth.IAuthRule;
import ca.uhn.fhir.rest.server.interceptor.auth.RuleBuilder;
import java.util.List;

/**
 *
 * @author Charles Chigoriwa
 */
public class DHIS2AuthInterceptor extends AuthorizationInterceptor {

    @Override
    public List<IAuthRule> buildRuleList(RequestDetails theRequestDetails) {
        String bearertoken = theRequestDetails.getHeader("Authorization");
        String token = bearertoken.split(" ")[1];
        if (checkToken(token)) {
            return new RuleBuilder()
                    .allowAll()
                    .build();
        } else {
            return new RuleBuilder()
                    .denyAll()
                    .build();
        }

    }
    
    private boolean checkToken(String token){
        return true;
    }

}
