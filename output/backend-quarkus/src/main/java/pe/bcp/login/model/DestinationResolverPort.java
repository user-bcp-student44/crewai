// DestinationResolverPort.java
package pe.bcp.login.port;

import java.util.Optional;
import pe.bcp.login.domain.Destination;

/** ADAPTADOR PENDIENTE: la fuente de la identidad visible del destino no fue especificada. */
public interface DestinationResolverPort {

    Optional<Destination> resolve(String providerId, String channel, String destinationType, String identifier);
}