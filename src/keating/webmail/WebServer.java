package keating.webmail;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.StringTokenizer;

/**
 * WebServer.java
 * 
 * Barebones single-threaded HTTP server to handle the webmail form. For
 * simplicity, all connections are non-persistent.
 *  
 * The WebServer also serves as the entry point to the application
 * 
 * @author Andrew Keating
 */

public class WebServer {

  private ServerSocket server;
  private Socket socket;
  private BufferedReader reader;
  private BufferedWriter writer;

  /**
   * Constructs a new WebServer on the specified port and listens for requests
   * @param port Numerical port (<= 65535)
   */
  public WebServer(int port) {
    try {
      server = new ServerSocket(port);
    }
    catch(IOException e) {
      throw new IllegalArgumentException(e.getMessage());
    }
  }

  /**
   * Starts the web server, accepting requests on the socket
   */
  public void start() {
    while(true) {
      try {
        try {
          socket = server.accept();
          reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
          writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
          processRequest(reader.readLine());
        }
        finally {
          // Connections are non-persistent, so we just close after handling the request
          if(reader != null) reader.close();
          if(writer != null) writer.close();
          if(socket != null) socket.close();
        }
      }
      catch(IOException e) {
        System.out.println("Error handling client: " + e.getMessage());
      }
    }
  }

  /**
   * Sends an HTTP response to the client
   * @param response The response to send
   */
  private void sendResponse(String response) {
    try {
      writer.write(response);
      writer.flush();
    } 
    catch (IOException e) {
      System.out.println("Error sending response: " + e.getMessage());
    }
  }

  /**
   * Serves delivery failure page with error from SMTP server
   * This is achieved by updating failure.html and redirecting
   * the client with a 301
   * @param message Failure message
   */
  private void sendFail(String message) {
    StringBuffer httpResponse = new StringBuffer();
    httpResponse.append("HTTP/1.1 301 Moved Permanently\r\n");
    httpResponse.append("Location: /failure.html\r\n");
    File f = new File("../html/" + "failure.html");
    FileWriter fwriter = null;
    try {
      try {
        fwriter = new FileWriter(f);
        fwriter.write("<html><head><title>Delivery Failure</title><body>Delivery Failure: " + message + "<br /><a href=\"form.html\">Back</a></body></html>");
      }
      finally {
        if(fwriter != null) fwriter.close();
      }
    }
    catch(IOException e) {
      System.out.println("Error writing file: " + e.getMessage());
    }
    
    httpResponse.append("Content-Type: text/html;charset=iso-8859-15\r\n");
    httpResponse.append("\r\n");
    sendResponse(httpResponse.toString());
  }

  /**
   * Processes a client's HTTP request
   * Validates input and sends the proper response, updating the email status page as necessary
   * @param request The input request from a client
   */
  private void processRequest(String request) {
    try {
      StringTokenizer tokenizer = new StringTokenizer(request);
      String requestType = "";
      if(tokenizer.hasMoreTokens()) {
        requestType = tokenizer.nextToken();
      }
      else {
        sendMalformedHttp();
        return;
      }

      // Handle HTTP GET request - Serve requested file if it exists
      String filename = "";
      StringBuffer httpResponse = new StringBuffer();
      if(requestType.equals("GET")) {
        if(!tokenizer.hasMoreTokens()) {
          filename = "/";
        }
        else {
          filename = tokenizer.nextToken();
        }

        // Serve the Webmail form by default
        if(filename.equals("/")) {
          filename += "form.html";
        }

        File f = new File("../html/" + filename.substring(1));  
        if(f.exists()) {
          httpResponse.append("HTTP/1.1 200 OK\r\n");
          httpResponse.append("Content-Type: text/html;charset=utf-8\r\n");
          httpResponse.append("Connection: close\r\n");
          httpResponse.append("\r\n");

          // Update the status page on a new request
          if(filename.equals("/status.html")) {
            String statusEntry = updateStatusPage();
            File statusPage = new File("../html/" + "status.html");
            FileWriter fwriter = null;
            try {
              fwriter = new FileWriter(statusPage);
              fwriter.write(statusEntry.toString());
            }
            finally {
              if (fwriter != null) fwriter.close();
            }
          }

          // Write the file contents into the response
          StringBuffer fileContents = new StringBuffer();
          BufferedReader fileReader = null;
          try {
            fileReader = new BufferedReader(new FileReader(f));
            String line = "";
            while((line = fileReader.readLine()) != null) {
              fileContents.append(line);
            }
          }
          finally {
            if(fileReader != null) fileReader.close();
          }
          httpResponse.append(fileContents.toString() + "\r\n");
        }
        else {
          // If the requested page doesn't exist, respond with a 404
          httpResponse.append("HTTP/1.1 404 Not Found\r\n");
          httpResponse.append("Content-Type: text/html;charset=iso-8859-15\r\n");
          httpResponse.append("Connection: close\r\n");
          httpResponse.append("\r\n");
          httpResponse.append("<html><body>Page not found (Error 404)</html></body>\r\n");
        }
      }
      // Handle HTTP POST request (client is sending an email via the form)
      else if(requestType.equals("POST")) {
        String line = "";
        boolean lengthFound = false;
        int length = 0;

        // Pass through the HTTP headers to find the content length
        try {
          line = reader.readLine();
          while(line != null && !line.equals("")){
            line = reader.readLine();
            if(line.contains("Content-Length")) {
              lengthFound = true;
              try {
                length = Integer.parseInt(line.substring(line.indexOf(" ")+1));
              }
              catch(NumberFormatException e) {
                length = -1;
              }
            }
          }
        }
        catch(InterruptedIOException e) {
          length = -1;
        }

        if(!lengthFound || length < 0) {
          // Request must contain content length or else it is invalid
          sendMalformedHttp();
          return;
        }
        else {
          // Process request content to determine email fields
          char content[] = new char[length];
          reader.read(content);
          String urlString = new String(content);
          String to = "";
          String from = "";
          String subject = "";
          String smtpServer = "";
          String message = "";
          String delay = "";
          urlString = URLDecoder.decode(urlString, "ISO-8859-15");

          try {
            // Read parameters one by one, advancing urlString to the next ampersand after each parameter
            from = urlString.substring(urlString.indexOf("from=") + 5, urlString.indexOf("&"));
            urlString = urlString.substring(urlString.indexOf("&") + 1, urlString.length());
            to = urlString.substring(urlString.indexOf("to=") + 3, urlString.indexOf("&"));
            urlString = urlString.substring(urlString.indexOf("&") + 1, urlString.length());
            subject = urlString.substring(urlString.indexOf("subject=") + 8, urlString.indexOf("&"));
            urlString = urlString.substring(urlString.indexOf("&") + 1, urlString.length());
            smtpServer = urlString.substring(urlString.indexOf("smtpserver=") + 11, urlString.indexOf("&"));
            urlString = urlString.substring(urlString.indexOf("&") + 1, urlString.length());
            message = urlString.substring(urlString.indexOf("message=") + 8, urlString.indexOf("&"));
            urlString = urlString.substring(urlString.indexOf("&") + 1, urlString.length());
            delay = urlString.substring(urlString.indexOf("delay=") + 6, urlString.length());
          }
          catch(Exception e) {
            // If there are any problems with the URL, serve a 400 Bad Request
            sendMalformedHttp();
          }

          // The URL was structured properly, so now we can validate the input
          String mailStatus;
          int sendDelay = 0;

          if(!delay.equals("")) {
            try {
              sendDelay = Integer.parseInt(delay);
            }
            catch(NumberFormatException nfe) {
              // Ignore invalid delays - just send right away
            }
          }

          // Validate to and from addresses
          if(to.equals("") || from.equals("")) {
            sendFail("Both TO and FROM addresses must be specified");
            return;
          }

          // One and only one '@' symbol
          if(!to.contains("@") || to.indexOf("@") != to.lastIndexOf("@") || !to.contains(".")){
            sendFail("Invalid TO address");
            return;
          }

          // One and only one '@' symbol
          if(!from.contains("@") || from.indexOf("@") != from.lastIndexOf("@") || !to.contains(".")){
            sendFail("Invalid FROM address");
            return;
          }

          SMTPClient smtpClient = SMTPClient.getInstance();
          // If the user requested a delay, we queue up the message to be sent later and redirect to the status page
          if(sendDelay > 0) {
            EmailMessage m = new EmailMessage(to, from, subject, smtpServer, message);
            smtpClient.sendMail(m, Integer.parseInt(delay));
            httpResponse.append("HTTP/1.1 301 Moved Permanently\r\n");
            httpResponse.append("Location: /status.html\r\n");
            httpResponse.append("Content-Type: text/html;charset=iso-8859-15\r\n");
            httpResponse.append("Connection: close\r\n");
            httpResponse.append("\r\n");
          }
          else {
            // Otherwise, we can send the message right away
            EmailMessage m = new EmailMessage(to, from, subject, smtpServer, message);
            mailStatus = smtpClient.sendMail(m, 0);

            httpResponse.append("HTTP/1.1 301 Moved Permanently\r\n");
            // Determine if the message succeeded or not and redirect accordingly
            if(mailStatus.equals("Success")) {
              httpResponse.append("Location: /success.html\r\n");
            }
            else {
              httpResponse.append("Location: /failure.html\r\n");
              File f = new File("../html/" + "failure.html");
              FileWriter fwriter = null;
              try {
                fwriter = new FileWriter(f);
                fwriter.write("<html><head><title>Delivery Failure</title><body>Delivery Failure: " + mailStatus + "<br /><a href=\"form.html\">Back</a></body></html>");
              }
              finally {
                if (fwriter != null) fwriter.close();
              }
            }
          }
        }
      }
      else {
        // We only support GET and POST
        sendMalformedHttp();
        return;
      }

      // Serve the response to the client
      sendResponse(httpResponse.toString());
    }
    catch(IOException e) {
      System.out.println(e.getMessage());
    }
  }

  /**
   * Serves a 400 Bad Request to the client
   */
  private void sendMalformedHttp() {
    StringBuffer malformedHttpResponse = new StringBuffer();
    malformedHttpResponse.append("HTTP/1.1 400 Bad Request\r\n");
    malformedHttpResponse.append("Content-Type: text/html;charset=iso-8859-15\r\n");
    malformedHttpResponse.append("Connection: close\r\n");
    malformedHttpResponse.append("\r\n");
    malformedHttpResponse.append("<html><body>Bad Request (Error 400)</body></html>\r\n");
    sendResponse(malformedHttpResponse.toString());
  }

  /**
   * Updates the contents of the status page
   * @return the updated HTML to be served
   */
  private String updateStatusPage() {
    StringBuffer statusEntry = new StringBuffer();
    statusEntry.append("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html;charset=iso-8859-15\" /><title>Status Page</title></head><body>");
    statusEntry.append("<a href=\"form.html\">Back</a> <a href=\"status.html\">Refresh</a><br />");
    statusEntry.append("<table border=\"1\" empty-cells=\"show\"><tr><td>To</td><td>From</td><td>Subject</td><td>Status</td><td>Submitted Time</td><td>Delivered Time</td></tr>");
    
    SMTPClient smtpClient = SMTPClient.getInstance();
    ArrayList<EmailMessage> messages = smtpClient.getMessages();
    for(int i = 0; i < messages.size(); i++) {
      boolean pending = false;

      statusEntry.append("<tr>");
      statusEntry.append("<td>");
      statusEntry.append(messages.get(i).getTo() + "</td> ");
      statusEntry.append("<td>");
      statusEntry.append(messages.get(i).getFrom() + "</td> ");
      statusEntry.append("<td>");
      statusEntry.append(messages.get(i).getSubject() + "</td> ");
      statusEntry.append("<td>");
      String status = messages.get(i).getStatus();
      if(status.equals("Pending"))
        pending = true;
      statusEntry.append(status + "</td> ");
      statusEntry.append("<td>");
      statusEntry.append(messages.get(i).getSubmitTime() + "</td> ");
      statusEntry.append("<td>");
      if(pending)
        statusEntry.append("Pending</td>");
      else { 
        if(messages.get(i).getDeliveryTime() == null) {
          messages.get(i).setDeliveryTime("Failed</td>");
        }
        statusEntry.append(messages.get(i).getDeliveryTime() + "</td>\n");
      }
      statusEntry.append("</tr>");
    }
    statusEntry.append("</table></body></html>");

    return statusEntry.toString();
  }

  public static void main(String[] args) {
    // Launch WebServer on port 8080
    WebServer s = new WebServer(8080);
    s.start();
  }
}
