package ca.stellardrift.mcannouncer.discord;

import java.net.URI;

/**
 * Indicates an API endpoint that can be contacted
 */
public interface ApiEndpoint {

    String description();

    URI url();

}
