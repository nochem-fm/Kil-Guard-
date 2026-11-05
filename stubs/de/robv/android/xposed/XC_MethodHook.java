package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XCallback;
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() { }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }
    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }
    public static final class MethodHookParam extends XCallback.Param {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public void setResult(Object result) { }
    }
    public class Unhook { }
}
