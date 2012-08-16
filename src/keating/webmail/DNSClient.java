package keating.webmail;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Hashtable;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/**
 * DNSClient.java
 *
 * This class determines the mail server of a domain via a DNS MX lookup
 * 
 * @author Andrew Keating
 */
public class DNSClient {
  
  /**
   * Not intended to be instantiated
   */
  private DNSClient() { }
  

  /**
   * Performs an MX lookup on the input domain and returns the result
   * @param domain The domain to look up the MX record of
   * @return The IP address of the mail server, or "Unknown"
   * @throws UnknownHostException If an MX record does not exist for the host
   * @throws NamingException If an invalid hostname is entered
   */
  public static String mxLookup(String domain) throws UnknownHostException, NamingException {
    String address = "";
    NamingEnumeration<?> values = null;
    
    Hashtable<String, String> env = new Hashtable<String, String>();
    env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
    DirContext ictx = new InitialDirContext(env);
    Attributes a = ictx.getAttributes(domain, new String[] { "MX" });
    NamingEnumeration<?> all = a.getAll();
        
    if(all.hasMore()) {
      Attribute attr = (Attribute)all.next();
      values = attr.getAll(); 
      if(values.hasMore()) {
        address = (String)values.next();
        // Strip leading zero
        address = address.substring(2);
        // Stip trailing period
        address = address.substring(0, address.length() - 1);
      }
    }
    
    String ip = InetAddress.getByName(address).getHostAddress();
    
    return ip;
  }
}
