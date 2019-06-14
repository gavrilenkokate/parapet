'use strict';
var express = require('express');
var component = require('../controllers/componentController');
var router = express.Router();

router.get("/components/searchByName/:name", component.searchByName)
router.post('/components', component.publish);

module.exports = router;