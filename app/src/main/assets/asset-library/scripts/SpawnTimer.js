// Params: objectName=Enemy, interval=2
function start() { every(interval, function() { scene.spawn(objectName, transform.x, transform.y); }); }
