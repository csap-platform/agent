// Simple yet flexible JSON editor plugin.
// Turns any element into a stylable interactive JSON editor.

// Copyright (c) 2011 David Durman

// Licensed under the MIT license (http://www.opensource.org/licenses/mit-license.php).

// Dependencies:

// * jQuery
// * JSON (use json2 library for browsers that do not support JSON natively)

// Example:

//     var myjson = { any: { json: { value: 1 } } };
//     var opt = { change: function() { /* called on every change */ } };
//     /* opt.propertyElement = '<textarea>'; */ // element of the property field, <input> is default
//     /* opt.valueElement = '<textarea>'; */  // element of the value field, <input> is default
//     $('#mydiv').jsonEditor(myjson, opt);

(function( $ ) {

    $.fn.jsonEditor = function(json, options) {
        options = options || {};

        var K = function() {},
            onchange = options.change || K;

        return this.each(function() {
            JSONEditor($(this), json, onchange, options.propertyElement, options.valueElement);
        });
        
    };
    
    function JSONEditor(target, json, onchange, propertyElement, valueElement) {
        var opt = {
            target: target,
            onchange: onchange,
            original: json,
            propertyElement: propertyElement,
            valueElement: valueElement
        };
        construct(opt, json, opt.target);
//        $('.property, .value', opt.target).live('blur focus', function() {
//            $(this).toggleClass('editing');
//        });
        // jq 1.9 depreciates .live api
        target.on("blur focus", ".property, .value",  function() {
            $(this).toggleClass('editing');
        });
        
    }

    function isObject(o) { return Object.prototype.toString.call(o) == '[object Object]'; }
    function isArray(o) { return Object.prototype.toString.call(o) == '[object Array]'; }
    function isBoolean(o) { return Object.prototype.toString.call(o) == '[object Boolean]'; }
    function isNumber(o) { return Object.prototype.toString.call(o) == '[object Number]'; }
    function isString(o) { return Object.prototype.toString.call(o) == '[object String]'; }
    var types = 'object array boolean number string null';

    // Feeds object `o` with `value` at `path`. If value argument is omitted,
    // object at `path` will be deleted from `o`.
    // Example:
    //      feed({}, 'foo.bar.baz', 10);    // returns { foo: { bar: { baz: 10 } } }
    function feed(o, path, value) {
        var del = arguments.length == 2;
        
        console.log("jsonEditor: feed path: " + path + " value: " + value) ;
        if (path.indexOf('.') > -1) {
            var diver = o,
                i = 0,
                parts = path.split('.');
            for (var len = parts.length; i < len - 1; i++) {
                diver = diver[parts[i]];
                
            }
            if (del) {
            	//console.log("json structure diver " +  JSON.stringify( diver )) ;
            	
            	var itemBeingDeleted =  diver[parts[len - 1]] ;
            	//console.log("Deleting: " + itemBeingDeleted) ;
            	
            	
            	if ( isArray(diver)) {
                	var arrayIndex = diver.indexOf( itemBeingDeleted ); 
            		//console.log( "Object being deleted is in array, splicing:" + arrayIndex) ;
            		diver.splice(arrayIndex,1) ;
            	} else {
                	delete diver[parts[len - 1]];
            		//console.log( "Object being deleted is not in array") ;
            	}
            } else { 
            	diver[parts[len - 1]] = value;
            }
        } else {
            if (del) delete o[path];
            else o[path] = value;
        }
        return o;
    }

    // Get a property by path from object o if it exists. If not, return defaultValue.
    // Example:
    //     def({ foo: { bar: 5 } }, 'foo.bar', 100);   // returns 5
    //     def({ foo: { bar: 5 } }, 'foo.baz', 100);   // returns 100
    function def(o, path, defaultValue) {
        path = path.split('.');
        var i = 0;
        while (i < path.length) {
            if ((o = o[path[i++]]) == undefined) return defaultValue;
        }
        return o;
    }

    function error(reason) { 
    	if (window.console) { 
    		// console.error(reason); 
    	} 
    	// alert("Got an error:" + reason) ;
    }
    
    function parse(str) {
        var res;
        try { res = JSON.parse(str); }
        catch (e) { res = str; error('JSON parse failed on field:' + str); }
        return res;
    }

    function stringify(obj) {
        var res;
        try { res = JSON.stringify(obj); }
        catch (e) { res = 'null'; error('JSON stringify failed.'); }
        return res;
    }
    
    function addExpander(item) {
        if (item.children('.expander').length == 0) {
            var expander =   $('<span>',  { 'class': 'expander' });
            expander.bind('click', function() {
                var item = $(this).parent();
                item.toggleClass('expanded');

                console.log("Expanding: " + $(this).next().val()  + " parent path: " + $(this).parent().data("path")) ; 
                currentElementInJson=$(this).next().val();
                currentElementPath = $(this).parent().data("path") ;
            });
            item.prepend(expander);
        }
    }
    
    function hookForSorting( path ) {
    	if ( path == "jvms" || path == "osProcesses" 
    		|| path.indexOf("jvmPorts")!= -1|| path.indexOf("osProcessesList")!= -1 ) return true;
    	return false ;
    }
    
    function construct(opt, json, root, path) {
        path = path || '';

        root.children('.item').remove();
       
        // CSAP hook for sorting JVMS path
        var sortedKeys = json ;
        if ( hookForSorting( path ) ) {
        	// console.log("path" + path) ;
        	sortedKeys = new Array() ;
        	 for (var key in json) {
        		 sortedKeys.push(key) ;
        	 }
        	 sortedKeys.sort( function(a,b) {

						if ( a.toUpperCase() < b.toUpperCase()) return -1 ; else return 1;
				} );
        }
        
        for (var item in sortedKeys) {
        	var key = item;
        	if ( hookForSorting( path ) ) {
        		key = sortedKeys[ item ] ;
        		// console.log("key " + key) ;
        	}
            if (!json.hasOwnProperty(key)) continue;

            var item     = $('<div>',   { 'class': 'item', 'data-path': path }),
                property =   $(opt.propertyElement || '<input>', { 'class': 'property' }),
                value    =   $(opt.valueElement || '<input>', { 'class': 'value'    });

            if (isObject(json[key]) || isArray(json[key])) {
                addExpander(item);
                
             // Custom hook for csap
                value.css("display", "none") ;

            }
            
            // Custom hook for csap
            property.addClass( key ) ;
            
            // if ( isObject( json[key]) ) value.css("display: none") ;
            item.append(property).append(value);
            root.append(item);
            
            property.val(key).attr('title', key);
            
            var val = stringify(json[key]);
            //CSAP hook for getting rid of quotes
            if ( isString(json[key]))
            	val = val.substring(1,val.length-1) ;
            value.val(val).attr('title', val);

            assignType(item, json[key]);

            property.change(propertyChanged(opt));
            value.change(valueChanged(opt));
            
            if (isObject(json[key]) || isArray(json[key])) {
                construct(opt, json[key], item, (path ? path + '.' : '') + key);
            }
        }
    }

    function updateParents(el, opt) {
        $(el).parentsUntil(opt.target).each(function() {
            var path = $(this).data('path');
            path = (path ? path + '.' : path) + $(this).children('.property').val();
            var val = stringify(def(opt.original, path, null));
            $(this).children('.value').val(val).attr('title', val);
        });
    }

    function propertyChanged(opt) {
        return function() {
            var path = $(this).parent().data('path');
            
            // csap hook - do not error on empty strings
            var valueInput = $(this).next() ;
            if ( !valueInput.hasClass("value")  ) {
            	// 1 button was added
            	if ( $(this).next().next().hasClass("value")  ) {
            		valueInput = $(this).next().next() ;
            	} else {
            		// 2 buttons added
            		valueInput = $(this).next().next().next() ;
            	}
            }
            
            var parsedJsonFromValueInput = parse( valueInput.val()) ;

           // console.log( "prop changed: " + JSON.stringify( parsedJsonFromValueInput )  ) ;
           var     newKey = $(this).val(),
                oldKey = $(this).attr('title');
            
           //  console.log("oldKey: " + oldKey + " newKey: " + newKey ) ;
//            if ( path == "" ) {
//            	jAlert("Top level keys cannot be modified") ;
//            	$(this).val(oldKey);
//            	// csap custom - do not allow editing of top level elements
//            	return ;
//            }
            

            $(this).attr('title', newKey);

            feed(opt.original, (path ? path + '.' : '') + oldKey);
            if (newKey) feed(opt.original, (path ? path + '.' : '') + newKey, parsedJsonFromValueInput);

            updateParents(this, opt);

            if (!newKey) $(this).parent().remove();
            
            opt.onchange();
        };
    }

    function valueChanged(opt) {
        return function() {
            var key = $(this).prev().val(),
                val = $(this).val()  || 'null',
                item = $(this).parent(),
                path = item.data('path');
            
            console.log("jsonEditor: valueChanged: " + val) ;

            if ( val.charAt(0) != '"' && val.charAt(0) != "{" && val.charAt(0) != "[" )
            	val = '"' + $(this).val() + '"' || 'null';
            
            val=parse(val) ;

            // console.log("jsonEditor: after parsing: " + val) ;
            // csap hook - we insert delete images - so this should go back an extra element
            if ( !$(this).prev().hasClass("property") ) {
            	key = $(this).prev().prev().val() ;
            	// clusters actually have and add and delete icon. so we traverse back twice
            	if ( !$(this).prev().prev().hasClass("property") ) {
            		key = $(this).prev().prev().prev().val() ;
            	}
            }

            feed(opt.original, (path ? path + '.' : '') + key, val);
            if ((isObject(val) || isArray(val)) && !$.isEmptyObject(val)) {
                construct(opt, val, item, (path ? path + '.' : '') + key);
                addExpander(item);
            } else {
                item.find('.expander, .item').remove();
            }

            assignType(item, val);

            updateParents(this, opt);
            
            opt.onchange();
        };
    }
    
    function assignType(item, val) {
        var className = 'null';
        
        if (isObject(val)) className = 'object';
        else if (isArray(val)) className = 'array';
        else if (isBoolean(val)) className = 'boolean';
        else if (isString(val)) className = 'string';
        else if (isNumber(val)) className = 'number';

        // console.log("jsonEditor: assignType, val:  " + val + " className: " + className) ;
        
        item.removeClass(types);
        item.addClass(className);
    }

})( jQuery );
