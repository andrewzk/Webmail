package keating.webmail;

import java.io.BufferedReader;
import keating.webmail.SMTPClient;
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
 * Barebones single-threaded HTTP server to handle the email sending form
 * 
 * @author Andrew Keating
 */

public class WebServer {

  private BufferedReader reader;
  private BufferedWriter writer;
  private Socket socket;
  private int messageId; // Global message ID counter
  private static ArrayList<EmailMessage> messages; // Collection of email status messages

  /**
   * Constructs a new WebServer on the specified port and listens for requests
   * @param port Numerical port (1-65535)
   * @param timeout Amount of time to wait before a read times out (milliseconds)
   */
  public WebServer(int port, int timeout) {
    messages = new ArrayList<EmailMessage>();

    try {
      ServerSocket server = new ServerSocket(port);

      while(true) {
        socket = server.accept();
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        socket.setSoTimeout(timeout);
        processRequest(reader.readLine());    
      }
    }
    catch(IOException e) {
      System.out.println(e.getMessage());
    }
  }

  /**
   * Sends an HTTP response to the client
   * @param response The response to send
   */
  public void sendResponse(String response) {
    try {
      writer.write(response);
      writer.flush();
      writer.close();
    } 
    catch (IOException e) {
      System.out.println(e.getMessage());
    }
  }

  /**
   * Serves delivery failure page with error from SMTP server
   * @param message Failure message
   */
  private void sendFail(String message){
    String httpResponse = "";
    httpResponse += "HTTP/1.1 301 Moved Permanently\r\n";
    httpResponse += "Location: /failure.html\r\n";
    File f = new File("../html/" + "failure.html");
    try {
      FileWriter fwriter = new FileWriter(f);
      fwriter.write("<html><head><title>Delivery Failure</title><body>Delivery Failure: " + message + "<br /><a href=\"form.html\">Back</a></body></html>");
      fwriter.flush();
      fwriter.close();
    }
    catch(IOException e) {
      System.out.println("Error writing file: " + e.getMessage());
    }
    httpResponse += "Content-Type: text/html;charset=iso-8859-15\r\n";
    httpResponse += "\r\n";
    sendResponse(httpResponse);
  }

  /**
   * Processes a client's HTTP request
   * Validates input and sends the proper response, updating the email status page as necessary
   * @param request The input request from a client
   */
  public void processRequest(String request) {
    BufferedReader fileReader = null;
    StringBuffer httpResponse = new StringBuffer();
    String filename = "";

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
      if(requestType.equals("GET")) {
        if(!tokenizer.hasMoreTokens()) {
          filename = "/";
        }
        else {
          filename = tokenizer.nextToken();
        }

        if(filename.equals("/")) {
          filename += "form.html";
        }

        File f = new File("../html/" + filename.substring(1));

        if(f.exists()) {
          httpResponse.append("HTTP/1.1 200 OK\r\n");
          httpResponse.append("Content-Type: text/html;charset=utf-8\r\n");
          httpResponse.append("\r\n");

          // Update the status page on a new request
          if(filename.equals("/status.html")) {
            String statusEntry = updateStatusPage();
            File statusPage = new File("../html/" + "status.html");
            FileWriter fwriter = new FileWriter(statusPage);
            fwriter.write(statusEntry.toString());
            fwriter.flush();
            fwriter.close();
          }

          StringBuffer fileContents = new StringBuffer();
          fileReader = new BufferedReader(new FileReader(f));
          String line = "";
          while((line = fileReader.readLine()) != null) {
            fileContents.append(line);
          }
          httpResponse.append(fileContents.toString() + "\r\n");
        }
        else {
          httpResponse.append("HTTP/1.1 404 Not Found\r\n");
          httpResponse.append("Content-Type: text/html;charset=iso-8859-15\r\n");
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
          String data = new String(content);
          String to = "";
          String from = "";
          String subject = "";
          String smtpServer = "";
          String message = "";
          String delay = "";
          data = URLDecoder.decode(data, "ISO-8859-15");

          try {
            from = data.substring(data.indexOf("from=") + 5, data.indexOf("&")); 
            data = data.substring(data.indexOf("&") + 1,data.length());
            to = data.substring(data.indexOf("to=") + 3, data.indexOf("&"));
            data = data.substring(data.indexOf("&") + 1,data.length());
            subject = data.substring(data.indexOf("subject=") + 8, data.indexOf("&"));
            data = data.substring(data.indexOf("&") + 1,data.length());
            smtpServer = data.substring(data.indexOf("smtpserver=") + 11, data.indexOf("&"));
            data=data.substring(data.indexOf("&") + 1,data.length());
            message = data.substring(data.indexOf("message=") + 8, data.indexOf("&"));
            data=data.substring(data.indexOf("&") + 1,data.length());
            delay = data.substring(data.indexOf("delay=") + 6, data.length());
          }
          catch(Exception e) {
            // If there are any problems with the URL, serve a 400 Bad Request
            sendMalformedHttp();
          }

          // The URL was structured properly, so now we can validate the input

          SMTPClient mailclient = new SMTPClient(2000);
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

          // If the user requested a delay, we queue up the message to be sent later and redirect to the status page
          if(sendDelay > 0) {
            EmailMessage m = new EmailMessage(messageId, to, from, subject, smtpServer, "Pending", message);
            messages.add(m);
            messageId++;
            mailclient.sendMail(m, Integer.parseInt(delay));

            httpResponse.append("HTTP/1.1 301 Moved Permanently\r\n");
            httpResponse.append("Location: /status.html\r\n");
            httpResponse.append("Content-Type: text/html;charset=iso-8859-15\r\n");
            httpResponse.append("\r\n");
          }
          else {
            // Otherwise, we can send the message right away
            EmailMessage m = new EmailMessage(messageId, to, from, subject, smtpServer, "Pending", message);
            messages.add(m);
            messageId++;
            mailStatus = mailclient.sendMail(m);
            updateStatus(m.getId(), mailStatus);

            httpResponse.append("HTTP/1.1 301 Moved Permanently\r\n");
            // Determine if the message succeeded or not and redirect accordingly
            if(mailStatus.equals("Success")) {
              httpResponse.append("Location: /success.html\r\n");
            }
            else {
              httpResponse.append("Location: /failure.html\r\n");
              File f = new File("../html/" + "failure.html");
              FileWriter fwriter=new FileWriter(f);
              fwriter.write("<html><head><title>Delivery Failure</title><body>Delivery Failure: " + mailStatus + "<br /><a href=\"form.html\">Back</a></body></html>");
              fwriter.flush();
              fwriter.close();
            }
            httpResponse.append("Content-Type: text/html;charset=iso-8859-15\r\n");
            httpResponse.append("\r\n");
          }
        }
      }
      else {
        // Invalid request
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
    // Returning Malformed Status message
    String malformedHttpResponse="";
    malformedHttpResponse += "HTTP/1.1 400 Bad Request\r\n";
    malformedHttpResponse += "Content-Type: text/html;charset=iso-8859-15\r\n";
    malformedHttpResponse += "\r\n";
    malformedHttpResponse += "<html><body>Bad Request (Error 400)</body></html>\r\n";
    sendResponse(malformedHttpResponse);
  }

  /**
   * Updates the contents of the status page
   * @return the updated HTML to be served
   */
  private String updateStatusPage() {
    StringBuffer statusEntry = new StringBuffer();
    statusEntry.append("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html;charset=utf-8\" /><title>Status Page</title></head><body>");
    statusEntry.append("<a href=\"form.html\">Back</a> <a href=\"status.html\">Refresh</a><br />");
    statusEntry.append("<table border=\"1\" empty-cells=\"show\"><tr><td>To</td><td>From</td><td>Subject</td><td>Status</td><td>Submitted Time</td><td>Delivered Time</td></tr>");

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

  /**
   * Updates the status message for a particular email message
   * @param id The ID of the message to update
   * @param status The updated status
   */
  public static void updateStatus(int id, String status) {
    messages.get(id).setStatus(status);
  }

  public static void main(String[] args) {
    // Launch WebServer on port 8080 with 1 second timeout
    new WebServer(8080, 1000);
  }
}
