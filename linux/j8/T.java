public class T {
  private int acc;
  public int alpha(int n){ int s=0; for(int i=0;i<n;i++){ s+=i%7; } acc+=s; return s; }
  public int beta(int n){ int s=1; for(int i=1;i<=n;i++){ s=(s*i)%9973; } acc+=s; return s; }
  public String gamma(String p){ StringBuilder b=new StringBuilder(); for(int i=0;i<p.length();i++) b.append((char)(p.charAt(i)+1)); return b.toString(); }
  public static void main(String[] a){
    T t=new T();
    t.alpha(50); t.gamma("hello");     // beta deliberately NOT called
    System.out.println("APP_OK alpha="+t.alpha(10)+" gamma="+t.gamma("xy"));
  }
}
