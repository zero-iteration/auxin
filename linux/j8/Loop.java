public class Loop { public static void main(String[] a) throws Exception {
  T t = new T(); t.alpha(50); t.gamma("hi"); Thread.sleep(5000); System.out.println("APP_OK a="+t.alpha(5)); } }
