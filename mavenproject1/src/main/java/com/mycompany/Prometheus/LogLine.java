package com.mycompany.Prometheus;
import java.time.OffsetTime;
/**
 *
 * @author andrea
 */
public class LogLine {
  private String type;
  private OffsetTime time;
  private String process;
  private String message;

  public LogLine(String type, String time, String process, String message) {
    this.type = type;
    this.time = OffsetTime.parse(time);
    this.process = process;
    this.message = message;
  }

  public String getType() {
    return type;
  }

  public OffsetTime getTime() {
    return time;
  }

  public String getProcess() {
    return process;
  }

  public String getMessage() {
    return message;
  }

  public void printLogLine(int i) {
    System.out.println(i + 1 + " " + type + " " + time + " " + process + " " + message);
  }
  public boolean isError() {
    if (type.equals("errore")) {
      return true;
    } else return false;
  }

}