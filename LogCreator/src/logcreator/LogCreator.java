package logcreator;

import java.io.*;
import java.time.OffsetTime;
import java.util.concurrent.TimeUnit;

/*
Utility per simulare l'aggiornamento costante del file di log.
Ogni 8 secondi scrive dei log in modo casuale
supponendo che il log possa essere al 50% un log di errore
e che al 30% possa provenire dal kernel.
 */

public class LogCreator {
  private static final String newLine = System.getProperty("line.separator");

  public static void main(String[] args) throws InterruptedException {
    try {
      while (true) {
        for (int i = 0; i < 5; i++) {
          String log = "";
          String appname = "";
          OffsetTime t = OffsetTime.now();
          String time = t.toString().substring(0, t.toString().length() - 3) + "00";
          double rand = Math.random() * 10;
          double rand2 = Math.random() * 10;
          if (rand > 5) {
            log += "errore";
          } else {
            log += "default";
          }
          if(rand2 > 7){
              appname +=  "kernel";
          }else {
              appname += "userapp";
          }
          log += " " + time + "    "+ appname + "  log example";
          System.out.println(log);
          writeToFile(log);
        }
        TimeUnit.SECONDS.sleep(8);
      }
    } catch (InterruptedException e) {

    }
  }

  private static synchronized void writeToFile(String msg) {
    String fileName = "/Users/andrea/NetBeansProjects/mavenproject1/src/main/java/com/mycompany/Prometheus/system.log";
    PrintWriter printWriter = null;
    File file = new File(fileName);
    try {
      if (!file.exists()) file.createNewFile();
      printWriter = new PrintWriter(new FileOutputStream(fileName, true));
      printWriter.write(newLine + msg);
    } catch (IOException ioex) {} finally {
      if (printWriter != null) {
        printWriter.flush();
        printWriter.close();
      }
    }
  }

}