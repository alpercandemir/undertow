class Subject { void io(Runnable request) { try(var e=java.util.concurrent.Executors.newCachedThreadPool()) { e.submit(request); } } }
