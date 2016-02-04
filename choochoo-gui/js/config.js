/**
 * Created by Varun Seereeram on 9/16/15.
 */

//Next
var nxApp;
var topo;

// REST Config
var username = 'admin';
var password = 'admin';

var trainTopoUrl = '/api/restconf/config/choochoo:train-topology';
var trainOperationUrl = '/api/restconf/operations/choochoo:control-train';

// Internal Configurations
var anchorName = 'ChooChoo Controller';
