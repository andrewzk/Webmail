package keating.webmail;

import java.io.BufferedReader;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * SMTPClient.java
 *
 * This class handles sending email via SMTP
 * 
 * Compliant with RFCs: 2045, 2047, 2821 (see README for more details)
 *
 * @author Andrew Keating
 *
 */

public class SMTPClient {

  private static final SMTPClient instance = new SMTPClient();
  
  private ArrayList<EmailMessage> messages; // Collection of email status messages
  private BufferedReader reader;
  private BufferedWriter writer;
  private Socket socket;
  
  private static final int SMTP_PORT = 25;
  private static final int SMTP_TIMEOUT = 2000;

  private SMTPClient() {
    messages = new ArrayList<EmailMessage>();
  }
  
  /**
   * Get the active SMTPClient instance
   * @return an instance of SMTPClient
   */
  public static SMTPClient getInstance() {
    return instance;
  }

  /**
   * Helper method which dispatches the email
   * @param message The email contents
   * @return Status message detailing the success/failure of the delivery. 
   */
  private String sendMail(EmailMessage message) {
    String to = message.getTo();
    String from = message.getFrom();
    String subject = message.getSubject();
    String server = message.getServer();
    String data = message.getData();

    if(subject.equals("")) {
      subject = "(No Subject)";
      message.setSubject(subject);
    }

    try {
      // If SMTP server is left blank, use DNS MX lookup to determine the server
      if(server.equals("")) {
        String domain = getDomainFromAddress(to);

        try {
          server = DNSClient.mxLookup(domain);
        }
        // Return an error if no MX record exists
        catch(Exception e) {
          return "SMTP server not entered, and could not determine SMTP server for recipient's domain";
        }
      }

      // Set up socket connection
      try {
        try {
          socket = new Socket();

          SocketAddress address;
          address = new InetSocketAddress(server, SMTP_PORT);

          socket.connect(address, SMTP_TIMEOUT);
          reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
          writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        }
        catch(SocketTimeoutException e) {
          return "Connection to SMTP server timed out";
        }
        catch(IOException e1) {
          return "Connection to SMTP server unsuccessful";
        }

        // Check for 220 from server
        String line = "";
        try {
          line = reader.readLine();
        }
        catch(IOException e) {
          return "Connection to SMTP server unsuccessful";
        }

        int code = getCode(line);
        if(code != 220) {
          return "Connection to SMTP server unsuccessful (Error " + Integer.toString(code) + ")";
        }

        // Client opens connection to server and server responds with opening message
        String helo = "HELO test.domain\r\n";
        String heloResponse = sendMessage(helo);

        code = getCode(heloResponse);
        if(code != 250) {
          return "Connection to SMTP server unsuccessful (Error " + Integer.toString(code) + ")";
        }

        // Begin transmitting email headers, one by one with carriage returns. Check all response codes.
        String mailFrom = "MAIL FROM:<" + from + ">\r\n";
        String mailFromResponse = sendMessage(mailFrom);

        code = getCode(mailFromResponse);
        if(code != 250) {
          return "Error sending mail (Error " + Integer.toString(code) + ")";
        }

        String rcptTo = "RCPT TO:<" + to + ">\r\n";
        String rcptToResponse = sendMessage(rcptTo);

        code = getCode(rcptToResponse);
        if(code != 250) {
          return "Error sending mail (Error " + Integer.toString(code) + ")";
        }

        String dataStr = "DATA\r\n";
        String dataResponse = sendMessage(dataStr);

        code = getCode(dataResponse);
        if(code != 354) {
          return "Error sending mail (Error " + Integer.toString(code) + ")";
        }

        // Use an RFC2047 subject to provide support for non-ASCII characters
        String subjectMsg = "Subject: " + toRFC2047(subject) + "\r\n";
        sendMessageWithoutResponse(subjectMsg);

        SimpleDateFormat sdf = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");
        Date d = new Date();
        String dateMsg = "Date: " + sdf.format(d) + "\r\n";
        message.setDeliveryTime(sdf.format(d));
        sendMessageWithoutResponse(dateMsg);

        String toMsg = "To: " + to + "\r\n";
        sendMessageWithoutResponse(toMsg);

        String fromMsg = "From: " + from + "\r\n";
        sendMessageWithoutResponse(fromMsg);

        String mimeVersion = "MIME-Version: 1.0\r\n";
        sendMessageWithoutResponse(mimeVersion);

        String contentType = "Content-Type: text/plain; charset=ISO-8859-15\r\n";
        sendMessageWithoutResponse(contentType);

        String cte = "Content-Transfer-Encoding: quoted-printable\r\n";
        sendMessageWithoutResponse(cte);

        String blankMsg = "\r\n";
        sendMessageWithoutResponse(blankMsg);

        /**
         * In SMTP, a line containing a period signals the end of a message's body. If a 
         * user sends an email containing a single period prior to the end of the email, 
         * truncation results. To avoid this, single periods in message bodies are 
         * replaced with double periods, a technique known as dot stuffing.
         */
        if(data.startsWith(".")) {
          data = data.replaceFirst(".", "..");
        }
        if(data.equals(".")) {
          data = data.replace(".", "..");
        }
        if(data.contains("\n.")) {
          data = data.replace("\n.", "\n..");
        }

        String dataMsg = toQuotedPrintable(data) + "\r\n";
        sendMessageWithoutResponse(dataMsg);

        String periodMsg = ".\r\n";
        String periodResponse = sendMessage(periodMsg);

        code = getCode(periodResponse);
        if(code != 250) {
          return "Error sending mail (Error " + Integer.toString(code) + ")";
        }

        String quit = "QUIT\r\n";
        String quitResponse = sendMessage(quit);

        if(getCode(quitResponse) != 221) {
          return "Error disconnecting from SMTP server (Error " + Integer.toString(code) + ")";
        }
      }
      finally {
        // Clean up streams and socket
        if(reader != null) reader.close();
        if(writer != null) writer.close();
        if(socket != null) socket.close();
      }
    }
    catch(IOException e) {
      System.out.println("Error closing stream/socket: " + e.getMessage());
    }

    return "Success";
  }

  /**
   * Pulls the domain from an email address
   * @param to Input email address
   * @return The domain portion of the email address
   */
  private String getDomainFromAddress(String to) {
    int atIndex = to.indexOf('@');
    return to.substring(atIndex + 1);
  }
  
  /**
   * @return the list of sent email messages with their current statuses 
   */
  public ArrayList<EmailMessage> getMessages() {
    return messages;
  }

  /**
   * Sends an email after a specified delay. If a delay of 0 is specified, the email is 
   * sent immediately.
   * 
   * @param message Email message to be sent
   * @param delay Delay in milliseconds
   * @return The email's delivery status, which is "Pending" if the email was delayed
   */
  public String sendMail(final EmailMessage message, final int delay) {
    messages.add(message);
    
    if(delay < 1) {
      String status = sendMail(message);
      message.setStatus(status);
      return status;
    }
    
    int seconds = delay * 1000;
    Timer t = new javax.swing.Timer(seconds, new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        try {
          String status = sendMail(message);
          message.setStatus(status);
          String senderServer = DNSClient.mxLookup(getDomainFromAddress(message.getFrom()));
          EmailMessage reply = new EmailMessage(message.getFrom(), "noreply@ik2213.lab", "Your email: " + message.getSubject(), senderServer, "The status of your email is: " + status);
          sendMail(reply);
        }
        catch(Exception ex) {
          // We can't respond because the MX lookup failed. So do nothing.
        }
      }
    });
    t.setRepeats(false);
    t.start();
    
    return message.getStatus();
  }

  /**
   * Pulls the SMTP code from a message (see RFC2821)
   * @param message	A message from an SMTP server
   * @return The numerical response code
   */
  private int getCode(String message) {
    return Integer.parseInt(message.substring(0, 3));
  }

  /**
   * Converts an SMTP header to RFC2047 form
   * This allows us to support non-ASCII characters in the email headers
   * @param message
   * @return RFC2047-compliant String
   */
  public String toRFC2047(String message) {
    StringBuffer encoded = new StringBuffer(message.length());
    encoded.append("=?ISO-8859-15?Q?");
    int lineCounter = 16;

    for(int i = 0; i < message.length(); i++) {  
      char c = message.charAt(i);

      // #1: CRLF
      if(c == '\r') {
        if(i != message.length() - 1) {
          if(message.charAt(i+1) == '\n') {
            // CRLF detected
            i++;
            encoded.append("\r\n");
            lineCounter = 0;
          }
        }
      }

      // Printable ASCII chars don't need to be encoded
      else if( ((c >= 33 && c <= 60) || (c >= 62 && c <= 126)) && (c != '?' && c != '=' && c != '_')) {
        encoded.append(c);
        lineCounter++;
      }

      // Always encode space/tab characters
      else if((c == 9 || c == 32)) {
        encoded.append('=');
        encoded.append(toHexString(c));
        lineCounter += 3;
      }

      // #4: Line breaks in the text body
      else if(c == '\n') {
        encoded.append("\r\n");
      }

      else {
        encoded.append('=');
        encoded.append(toHexString(c));
        lineCounter += 3;
      }

      // End encoded words at 70 chars (limit is 75)
      if(lineCounter > 69) {
        encoded.append("?=" + "=?ISO-8859-15?Q?");
        lineCounter = 16;
      }
    }

    encoded.append("?=");

    return encoded.toString();
  }

  /**
   * Converts an email message body to quoted printable (RFC2821)
   * This is the standard for SMTP email encoding
   * @param message Email message body to be sent
   * @return RFC2821-compliant encoding of the message body
   */
  public String toQuotedPrintable(String message) {

    StringBuffer encoded = new StringBuffer(message.length());
    int lineCounter = 0;

    for(int i = 0; i < message.length(); i++) {  
      char c = message.charAt(i);

      // #1: CRLF
      if(c == '\r') {
        if(i != message.length() - 1) {
          if(message.charAt(i+1) == '\n') {
            // CRLF detected
            i++;
            encoded.append("\r\n");
            lineCounter = 0;
          }
        }
      }

      // #2: Printable ASCII chars
      else if((c >= 33 && c <= 60) || (c >= 62 && c <= 126)) {
        encoded.append(c);
        lineCounter++;
      }

      // #3: Space/Tab characters
      else if((c == 9 || c == 32)) {
        if(i != message.length() - 1) {
          char nextChar = message.charAt(i+1);
          if(nextChar != '\r' && nextChar != '\n') {
            encoded.append(c);
            lineCounter++;
          }
          else {
            // Need to encode the space/tab
            encoded.append('=');
            encoded.append(toHexString(c));
            lineCounter += 3;
          }
        }
      }

      // #4: Line breaks in the text body
      else if(c == '\n') {
        encoded.append("\r\n");
      }

      else {
        encoded.append('=');
        encoded.append(toHexString(c));
        lineCounter += 3;
      }

      // #5: Max 76 chars per line, but we'll be safe and cut it off at 72
      if(lineCounter > 70) {
        encoded.append("=\r\n");
        lineCounter = 0;
      }
    }

    return encoded.toString();
  }

  /**
   * Converts a character to its hexadecimal representation
   * @param c Character to convert
   * @return Hexadecimal String representation of the character
   */
  private String toHexString(char c) {
    String hexString = Integer.toHexString((int)c).toUpperCase();
    if(hexString.length() == 1) {
      hexString = "0" + hexString;
    }
    return hexString;
  }

  /**
   * Helper method which sends a message to the mail server and returns the response
   * @param message Message to send
   * @return Response from the mail server
   */
  private String sendMessage(String message) {
    String response = "";
    try {
      writer.write(message);
      writer.flush();
    }
    catch(IOException e) {
      System.out.println("Error sending message to server: " + e.getMessage());
    }

    try {
      response = reader.readLine();
    }
    catch(IOException e) {
      System.out.println("Error receiving server response: " + e.getMessage());
    }

    return response;
  }

  /**
   * Helper method which sends a message to the mail server but does not check for a response
   * @param message Message to send
   */
  private void sendMessageWithoutResponse(String message) {
    try {
      writer.write(message);
      writer.flush();
    }
    catch(IOException e) {
      System.out.println("Error sending message to server: " + e.getMessage());
    }
  }
}
